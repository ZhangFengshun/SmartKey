package dev.lovekey.open.ai

import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.URL
import java.net.URLEncoder

/** Cleartext is accepted only for literal private/loopback addresses and localhost. */
internal object EndpointPolicy {
    fun endpoint(settings: AiSettings): URL {
        val uri = validatedBase(settings)
        val path = (uri.path ?: "").trimEnd('/')
        val endpointPath = when (settings.provider) {
            ProviderKind.OPENAI_COMPATIBLE -> when {
                path.endsWith("/chat/completions") -> path
                path.isEmpty() -> "/v1/chat/completions"
                else -> "$path/chat/completions"
            }
            ProviderKind.ANTHROPIC -> when {
                path.endsWith("/messages") -> path
                path.isEmpty() -> "/v1/messages"
                else -> "$path/messages"
            }
            ProviderKind.GEMINI -> {
                val model = settings.model.removePrefix("models/")
                if (!model.matches(Regex("[A-Za-z0-9_.-]{1,160}"))) {
                    throw AiException("Gemini 模型名称格式不正确。")
                }
                when {
                    path.endsWith(":generateContent") -> path
                    path.isEmpty() -> "/v1beta/models/$model:generateContent"
                    else -> "$path/models/$model:generateContent"
                }
            }
            ProviderKind.OLLAMA -> when {
                path.endsWith("/api/chat") -> path
                path.endsWith("/api") -> "$path/chat"
                path.isEmpty() -> "/api/chat"
                else -> "$path/api/chat"
            }
        }
        return try { URI(uri.scheme.lowercase(), null, uri.host, uri.port, endpointPath, null, null).toURL() }
        catch (_: Exception) { invalidUrl() }
    }

    /** Pagination values are encoded on the configured origin; no service-supplied URL is followed. */
    fun catalogEndpoint(settings: AiSettings, cursor: String? = null): URL {
        val uri = validatedBase(settings)
        var path = (uri.path ?: "").trimEnd('/')
        val endpointPath = when (settings.provider) {
            ProviderKind.OPENAI_COMPATIBLE -> {
                path = path.removeSuffix("/chat/completions")
                when { path.endsWith("/models") -> path; path.isEmpty() -> "/v1/models"; else -> "$path/models" }
            }
            ProviderKind.ANTHROPIC -> {
                path = path.removeSuffix("/messages")
                when { path.endsWith("/models") -> path; path.isEmpty() -> "/v1/models"; else -> "$path/models" }
            }
            ProviderKind.GEMINI -> {
                if (path.endsWith(":generateContent")) path = path.substringBeforeLast("/models/", path)
                when { path.endsWith("/models") -> path; path.isEmpty() -> "/v1beta/models"; else -> "$path/models" }
            }
            ProviderKind.OLLAMA -> {
                path = path.removeSuffix("/chat").removeSuffix("/tags")
                when { path.endsWith("/api") -> "$path/tags"; path.isEmpty() -> "/api/tags"; else -> "$path/api/tags" }
            }
        }
        if (cursor != null && (cursor.isBlank() || cursor.length > 2_048 || cursor.any { it.isISOControl() })) {
            throw AiException("模型服务返回的分页信息无效，请重试或手动填写模型。")
        }
        if (cursor != null && settings.apiKey.isNotBlank() && cursor.contains(settings.apiKey)) {
            throw AiException("模型服务返回的分页信息无效，请重试或手动填写模型。")
        }
        val query = when (settings.provider) {
            ProviderKind.ANTHROPIC -> "limit=100" + (cursor?.let { "&after_id=" + URLEncoder.encode(it, "UTF-8") } ?: "")
            ProviderKind.GEMINI -> "pageSize=100" + (cursor?.let { "&pageToken=" + URLEncoder.encode(it, "UTF-8") } ?: "")
            else -> null
        }
        val originAndPath = try { URI(uri.scheme.lowercase(), null, uri.host, uri.port, endpointPath, null, null).toASCIIString() }
        catch (_: Exception) { invalidUrl() }
        return URL(originAndPath + (query?.let { "?$it" } ?: ""))
    }

    private fun validatedBase(settings: AiSettings): URI {
        if (settings.baseUrl.length > 2_048) invalidUrl()
        val uri = try { URI(settings.baseUrl.trim()) } catch (_: Exception) { invalidUrl() }
        val scheme = uri.scheme?.lowercase()
        val host = uri.host ?: invalidUrl()
        if (scheme !in listOf("https", "http") || uri.rawUserInfo != null ||
            uri.rawQuery != null || uri.rawFragment != null || uri.port > 65535 || uri.port == 0) invalidUrl()
        if (scheme == "http" && !isLocalHost(host)) {
            throw AiException("远程模型地址必须使用 HTTPS；HTTP 仅限本机或私有局域网地址。")
        }
        if ((uri.path ?: "").split('/').any { it == "." || it == ".." }) invalidUrl()
        return uri
    }

    fun isLocalHost(value: String): Boolean {
        val host = value.removePrefix("[").removeSuffix("]").lowercase()
        if (host == "localhost") return true
        if (host.contains(':') && !host.contains('%')) {
            val address = try { InetAddress.getByName(host) } catch (_: Exception) { return false }
            if (address !is Inet6Address) return false
            val first = address.address[0].toInt() and 255
            return address.isLoopbackAddress || address.isLinkLocalAddress || (first and 254) == 252
        }
        val parts = host.split('.')
        if (parts.size != 4 || parts.any { !it.matches(Regex("[0-9]{1,3}")) || (it.length > 1 && it.startsWith('0')) }) return false
        val bytes = parts.map { it.toInt() }
        if (bytes.any { it !in 0..255 }) return false
        return bytes[0] == 127 || bytes[0] == 10 ||
            (bytes[0] == 172 && bytes[1] in 16..31) ||
            (bytes[0] == 192 && bytes[1] == 168)
    }

    private fun invalidUrl(): Nothing = throw AiException("请填写有效的模型服务地址，不要在地址中包含密钥、查询参数或用户名。")
}
