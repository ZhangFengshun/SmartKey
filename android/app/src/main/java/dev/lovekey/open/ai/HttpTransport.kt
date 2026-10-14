package dev.lovekey.open.ai

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.SocketTimeoutException
import java.net.URL
import javax.net.ssl.SSLException

internal interface HttpTransport {
    fun post(call: ProviderCall): JSONObject
    fun get(url: URL, headers: Map<String, String>): JSONObject = throw UnsupportedOperationException("GET is not implemented")
}

/** One request per user action; reusable connections, cancellable sockets, no automatic retries/redirects. */
internal class UrlConnectionTransport : HttpTransport {
    override fun post(call: ProviderCall): JSONObject = request(call.url, call.headers, call.body, AiLimits.RESPONSE_BYTES)
    override fun get(url: URL, headers: Map<String, String>): JSONObject = request(url, headers, null, AiLimits.CATALOG_RESPONSE_BYTES)

    private fun request(url: URL, headers: Map<String, String>, body: JSONObject?, responseLimit: Int): JSONObject {
        val owner = Thread.currentThread()
        if (owner.isInterrupted) throw AiException("请求已取消。")
        val builder = okhttp3.Request.Builder().url(url).header("Accept", "application/json")
        headers.forEach { (name, value) -> builder.header(name, value) }
        if (body != null) builder.post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
        val call = client.newCall(builder.build())
        val cancellation = canceller.scheduleAtFixedRate({ if (owner.isInterrupted) call.cancel() }, 50, 50, java.util.concurrent.TimeUnit.MILLISECONDS)
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) throw statusError(response.code)
                val data = ByteArrayOutputStream()
                val responseBody = response.body ?: throw AiException("模型服务返回了空数据。")
                responseBody.byteStream().use { input ->
                    val buffer = ByteArray(8_192)
                    while (true) {
                        if (owner.isInterrupted) throw AiException("请求已取消。")
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (data.size() + count > responseLimit) throw AiException(if (body == null)
                            "模型列表内容过大，请手动填写模型名称。" else "模型返回内容过大，请更换模型或缩短输入。")
                        data.write(buffer, 0, count)
                    }
                }
                return try { JSONObject(data.toString("UTF-8")) }
                catch (_: Exception) { throw AiException("模型服务返回了无法识别的数据，请检查接口类型。") }
            }
        } catch (error: AiException) { throw error }
        catch (_: SocketTimeoutException) { throw AiException(if (owner.isInterrupted) "请求已取消。" else "模型请求超时，请检查网络或本地模型是否运行。") }
        catch (_: SSLException) { throw AiException("无法验证模型服务的安全连接，请检查 HTTPS 地址和证书。") }
        catch (_: IOException) { throw AiException(if (owner.isInterrupted) "请求已取消。" else "无法连接模型服务，请检查网络、服务地址和本地模型是否运行。") }
        catch (_: Exception) { throw AiException("模型请求失败，请检查服务配置后重试。") }
        finally { cancellation.cancel(false); call.cancel() }
    }

    companion object {
        private val client = okhttp3.OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(45, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build()
        private val canceller = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "SmartKey-request-cancel").apply { isDaemon = true }
        }
    }

    private fun statusError(status: Int) = AiException(when (status) {
        401, 403 -> "模型服务拒绝访问，请检查 API Key 和账号权限。"
        404 -> "模型或接口不存在，请检查模型名称和服务地址。"
        408, 504 -> "模型请求超时，请稍后重试。"
        413 -> "模型服务认为输入过长，请缩短后重试。"
        429 -> "模型服务额度不足或请求过于频繁，请检查额度并稍后重试。"
        in 300..399 -> "模型服务要求跳转，请直接配置最终服务地址。为保护密钥，本应用不会转发到跳转地址。"
        in 500..599 -> "模型服务暂时不可用，请稍后重试。"
        else -> "模型服务拒绝请求（HTTP $status），请检查接口类型与模型配置。"
    })
}
