package dev.lovekey.open.ai

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AiClientTest {
    private val request = ReplyRequest("今天真的好累")
    private val configured = AiSettings(model = "test-model", apiKey = "test-secret")
    private val output = JSONObject().put("candidates", JSONArray()
        .put(JSONObject().put("title", "温柔").put("text", "辛苦了，我在听。"))
        .put(JSONObject().put("title", "幽默").put("text", "给今天按个暂停键吧。"))
        .put(JSONObject().put("title", "陪伴").put("text", "有什么我可以帮忙的吗？"))).toString()

    @Test fun missingConfigurationDoesNotCallNetworkOrReturnTemplates() {
        val transport = FakeTransport()
        val client = AiClient(transport)
        assertTrue(error { client.generate(AiSettings(), request) }.message!!.contains("模型名称"))
        assertTrue(error { client.generate(configured.copy(apiKey = ""), request) }.message!!.contains("API Key"))
        assertEquals(0, transport.calls)
    }

    @Test fun demoModeIsExplicitAndNeverCallsNetwork() {
        val transport = FakeTransport()
        val candidates = AiClient(transport).generate(AiSettings(demoMode = true), request)
        assertEquals(3, candidates.size)
        assertTrue(candidates.all { it.title.startsWith("演示") })
        assertEquals(0, transport.calls)
    }

    @Test fun producesExactlyThreeCandidatesFromEachProviderEnvelope() {
        ProviderKind.entries.forEach { kind ->
            val envelope = when (kind) {
                ProviderKind.OPENAI_COMPATIBLE -> JSONObject().put("choices", JSONArray().put(JSONObject().put("message", JSONObject().put("content", output))))
                ProviderKind.ANTHROPIC -> JSONObject().put("content", JSONArray().put(JSONObject().put("type", "text").put("text", output)))
                ProviderKind.GEMINI -> JSONObject().put("candidates", JSONArray().put(JSONObject().put("content", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", output))))))
                ProviderKind.OLLAMA -> JSONObject().put("message", JSONObject().put("content", output))
            }
            val transport = FakeTransport(envelope)
            val candidates = AiClient(transport).generate(configured.copy(provider = kind, baseUrl = kind.defaultBaseUrl), request)
            assertEquals(3, candidates.size)
            assertEquals(1, transport.calls)
            assertEquals("辛苦了，我在听。", candidates.first().text)
        }
    }

    @Test fun serviceErrorsAreNotConvertedToDemoReplies() {
        val client = AiClient(object : HttpTransport {
            override fun post(call: ProviderCall): JSONObject = throw AiException("模型服务拒绝访问")
        })
        assertEquals("模型服务拒绝访问", error { client.generate(configured, request) }.message)
    }

    @Test fun malformedProviderOutputCannotExposeRawServiceDetails() {
        val client = AiClient(FakeTransport(JSONObject().put("error", "test-secret server private debug text")))
        val message = error { client.generate(configured, request) }.message!!
        assertFalse(message.contains("test-secret"))
        assertFalse(message.contains("debug"))
        assertFalse(configured.toString().contains("test-secret"))
    }

    @Test fun rejectsOversizeInputBeforeSendingAndNeverTruncatesItSilently() {
        val transport = FakeTransport()
        val message = error { AiClient(transport).generate(configured, request.copy(text = "a".repeat(AiLimits.TEXT + 1))) }.message!!
        assertTrue(message.contains("过长"))
        assertEquals(0, transport.calls)
    }

    @Test fun rejectsDuplicatesAndUnexpectedCandidateCount() {
        val client = AiClient(FakeTransport())
        val duplicate = JSONObject(output).apply {
            getJSONArray("candidates").getJSONObject(1).put("text", getJSONArray("candidates").getJSONObject(0).getString("text"))
        }
        assertTrue(error { client.parseCandidates(duplicate.toString()) }.message!!.contains("重复"))
        val missing = JSONObject(output).apply { getJSONArray("candidates").remove(2) }
        assertTrue(error { client.parseCandidates(missing.toString()) }.message!!.contains("数量"))
        assertEquals(3, client.parseCandidates("```json\n$output\n```").size)
    }

    private fun error(block: () -> Unit): AiException {
        val exception = runCatching(block).exceptionOrNull()
        assertTrue("Expected safe AiException", exception is AiException)
        return exception as AiException
    }

    private class FakeTransport(val response: JSONObject = JSONObject()) : HttpTransport {
        var calls = 0
        override fun post(call: ProviderCall): JSONObject { calls++; return response }
    }
}
