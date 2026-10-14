package dev.lovekey.open.ai

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class HttpTransportTest {
    @Test fun interruptedRequestReleasesWorkerBeforeServerResponds() {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val accepted = java.util.concurrent.CountDownLatch(1)
        val socket = AtomicReference<java.net.Socket>()
        val receiver = Thread {
            try { socket.set(server.accept()); accepted.countDown() } catch (_: Exception) {}
        }.apply { isDaemon = true; start() }
        val failure = AtomicReference<Throwable>()
        val worker = Thread {
            try { UrlConnectionTransport().get(java.net.URL("http://127.0.0.1:${server.localPort}/hang"), emptyMap()) }
            catch (error: Throwable) { failure.set(error) }
        }
        try {
            worker.start()
            assertTrue(accepted.await(3, java.util.concurrent.TimeUnit.SECONDS))
            worker.interrupt(); worker.join(2_000)
            assertFalse("Cancelled network request held the generation worker", worker.isAlive)
            assertTrue(failure.get() is AiException)
            assertTrue(failure.get().message!!.contains("取消"))
        } finally { socket.get()?.close(); server.close(); worker.interrupt(); receiver.join(1000) }
    }

    @Test fun catalogSizeLimitAllowsLargeMetadataButStillStopsOversizedLists() {
        LocalHttpServer { request, _ ->
            val metadataLength = if (request.path == "/large-catalog") AiLimits.RESPONSE_BYTES + 100 else AiLimits.CATALOG_RESPONSE_BYTES + 100
            HttpResponse(200, "{\"data\":[{\"id\":\"model\",\"description\":\"" + "x".repeat(metadataLength) + "\"}]}")
        }.use { server ->
            val transport = UrlConnectionTransport()
            val allowed = transport.get(java.net.URL("http://127.0.0.1:${server.port}/large-catalog"), emptyMap())
            assertEquals("model", allowed.getJSONArray("data").getJSONObject(0).getString("id"))
            val error = runCatching { transport.get(java.net.URL("http://127.0.0.1:${server.port}/oversized-catalog"), emptyMap()) }.exceptionOrNull()
            assertTrue(error is AiException)
            assertTrue(error!!.message!!.contains("列表内容过大"))
        }
    }

    @Test fun catalogGetSendsOnlyHeadersAndDoesNotForwardRedirects() {
        val redirected = AtomicBoolean(false)
        LocalHttpServer { request, port ->
            assertEquals("GET", request.method)
            assertEquals("", request.body)
            assertEquals("Bearer test-secret", request.headers["authorization"])
            when (request.path) {
                "/models" -> HttpResponse(200, "{\"data\":[{\"id\":\"local-model\"}]}")
                "/redirect-models" -> HttpResponse(302, "", mapOf("Location" to "http://127.0.0.1:$port/destination"))
                else -> { redirected.set(true); HttpResponse(200, "{}") }
            }
        }.use { server ->
            val transport = UrlConnectionTransport()
            val headers = mapOf("Authorization" to "Bearer test-secret")
            val result = transport.get(java.net.URL("http://127.0.0.1:${server.port}/models"), headers)
            assertEquals("local-model", result.getJSONArray("data").getJSONObject(0).getString("id"))
            val error = runCatching { transport.get(java.net.URL("http://127.0.0.1:${server.port}/redirect-models"), headers) }.exceptionOrNull()
            assertTrue(error is AiException)
            assertFalse(redirected.get())
        }
    }

    @Test fun postsUtf8AndReturnsJsonWithoutFollowingRedirects() {
        val received = AtomicReference("")
        val redirected = AtomicBoolean(false)
        LocalHttpServer { request, port ->
            when (request.path) {
                "/ok" -> { received.set(request.body); HttpResponse(200, "{\"value\":\"你好\"}") }
                "/redirect" -> HttpResponse(302, "", mapOf("Location" to "http://127.0.0.1:$port/destination"))
                else -> { redirected.set(true); HttpResponse(200, "{}") }
            }
        }.use { server ->
            val transport = UrlConnectionTransport()
            val result = transport.post(call(server.port, "ok", JSONObject().put("input", "你好")))
            assertEquals("你好", result.getString("value"))
            assertEquals("你好", JSONObject(received.get()).getString("input"))
            val error = runCatching { transport.post(call(server.port, "redirect")) }.exceptionOrNull()
            assertTrue(error is AiException)
            assertTrue(error!!.message!!.contains("跳转"))
            assertFalse(redirected.get())
        }
    }

    @Test fun rawAuthenticationErrorIsNeverDisplayed() {
        LocalHttpServer { _, _ -> HttpResponse(401, "test-secret and private server detail") }.use { server ->
            val error = runCatching { UrlConnectionTransport().post(call(server.port, "private-error")) }.exceptionOrNull()
            assertTrue(error is AiException)
            assertTrue(error!!.message!!.contains("API Key"))
            assertFalse(error.message!!.contains("test-secret"))
            assertFalse(error.message!!.contains("private server"))
        }
    }

    @Test fun oversizedServiceResponseIsRejected() {
        LocalHttpServer { _, _ -> HttpResponse(200, "x".repeat(AiLimits.RESPONSE_BYTES + 1)) }.use { server ->
            val error = runCatching { UrlConnectionTransport().post(call(server.port, "oversized")) }.exceptionOrNull()
            assertTrue(error is AiException)
            assertTrue(error!!.message!!.contains("过大"))
        }
    }

    private fun call(port: Int, path: String, body: JSONObject = JSONObject()) =
        ProviderCall(java.net.URL("http://127.0.0.1:$port/$path"), mapOf("Authorization" to "Bearer test-secret"), body)

    private data class HttpRequest(val method: String, val path: String, val body: String, val headers: Map<String, String>)
    private data class HttpResponse(val status: Int, val body: String, val headers: Map<String, String> = emptyMap())

    /** A loopback protocol fixture using APIs also present in the Android compilation classpath. */
    private class LocalHttpServer(handler: (HttpRequest, Int) -> HttpResponse) : Closeable {
        private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val port = socket.localPort
        private val thread = Thread({
            while (!socket.isClosed) {
                val client = try { socket.accept() } catch (_: Exception) { break }
                client.use {
                    try {
                        client.soTimeout = 2_000
                        val input = client.getInputStream()
                        val requestLine = line(input).split(' ')
                        val method = requestLine.first()
                        val path = requestLine.getOrElse(1) { "/" }
                        var contentLength = 0
                        val requestHeaders = mutableMapOf<String, String>()
                        while (true) {
                            val header = line(input)
                            if (header.isEmpty()) break
                            requestHeaders[header.substringBefore(':').lowercase()] = header.substringAfter(':').trim()
                            if (header.startsWith("Content-Length:", ignoreCase = true)) contentLength = header.substringAfter(':').trim().toInt()
                        }
                        val body = ByteArray(contentLength)
                        var offset = 0
                        while (offset < body.size) {
                            val count = input.read(body, offset, body.size - offset)
                            if (count < 0) break
                            offset += count
                        }
                        val response = handler(HttpRequest(method, path, String(body, Charsets.UTF_8), requestHeaders), port)
                        val bytes = response.body.toByteArray(Charsets.UTF_8)
                        val headers = buildString {
                            append("HTTP/1.1 ${response.status} Test\r\n")
                            append("Content-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n")
                            response.headers.forEach { (name, value) -> append("$name: $value\r\n") }
                            append("\r\n")
                        }
                        client.getOutputStream().apply { write(headers.toByteArray(Charsets.US_ASCII)); write(bytes); flush() }
                    } catch (_: Exception) { /* A rejected oversized response may close the client socket early. */ }
                }
            }
        }, "provider-http-test").apply { isDaemon = true; start() }

        override fun close() { socket.close(); thread.join(1_000) }

        companion object {
            private fun line(input: InputStream): String {
                val bytes = ByteArrayOutputStream()
                while (bytes.size() < 8_192) {
                    val value = input.read()
                    if (value < 0 || value == 10) break
                    if (value != 13) bytes.write(value)
                }
                return bytes.toString("US-ASCII")
            }
        }
    }
}
