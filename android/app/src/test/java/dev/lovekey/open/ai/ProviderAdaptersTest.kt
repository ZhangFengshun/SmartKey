package dev.lovekey.open.ai

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ProviderAdaptersTest {
    @Test fun fastThinkingOptionsAreRestrictedToVerifiedDeepSeekEndpoints() {
        val configured = AiSettings(baseUrl = "https://api-inference.modelscope.cn/v1", model = "deepseek-ai/DeepSeek-V4.1-Flash", apiKey = "test")
        val fast = OpenAiCompatibleProvider.build(configured, prompt).body
        assertEquals("disabled", fast.getJSONObject("thinking").getString("type"))
        assertFalse(fast.getBoolean("enable_thinking"))
        assertFalse(OpenAiCompatibleProvider.build(configured.copy(baseUrl = "https://example.com/v1"), prompt).body.has("thinking"))
        assertFalse(OpenAiCompatibleProvider.build(configured.copy(model = "other-model"), prompt).body.has("thinking"))
    }

    private val prompt = ComposedPrompt("system instruction", "user text")
    private fun settings(kind: ProviderKind) = AiSettings(provider = kind, baseUrl = kind.defaultBaseUrl, model = "test-model", apiKey = "test-secret")

    @Test fun openAiUsesChatCompletionsAndBearerOutsideBody() {
        val call = OpenAiCompatibleProvider.build(settings(ProviderKind.OPENAI_COMPATIBLE), prompt)
        assertEquals("/v1/chat/completions", call.url.path)
        assertEquals("Bearer test-secret", call.headers["Authorization"])
        assertEquals("system", call.body.getJSONArray("messages").getJSONObject(0).getString("role"))
        assertFalse(call.body.toString().contains("test-secret"))
        assertFalse(call.body.getBoolean("stream"))
        val response = JSONObject().put("choices", JSONArray().put(JSONObject().put("message", JSONObject().put("content", "result"))))
        assertEquals("result", OpenAiCompatibleProvider.read(response))
    }

    @Test fun anthropicUsesTopLevelSystemAndRequiredVersion() {
        val call = AnthropicProvider.build(settings(ProviderKind.ANTHROPIC), prompt)
        assertEquals("/v1/messages", call.url.path)
        assertEquals("test-secret", call.headers["x-api-key"])
        assertEquals("2023-06-01", call.headers["anthropic-version"])
        assertEquals("system instruction", call.body.getString("system"))
        assertEquals(2_048, call.body.getInt("max_tokens"))
        assertEquals(1, call.body.getJSONArray("messages").length())
        val response = JSONObject().put("content", JSONArray()
            .put(JSONObject().put("type", "thinking").put("thinking", "private reasoning"))
            .put(JSONObject().put("type", "text").put("text", "result")))
        assertEquals("result", AnthropicProvider.read(response))
    }

    @Test fun geminiUsesHeaderKeyAndExtractsOnlyVisibleParts() {
        val call = GeminiProvider.build(settings(ProviderKind.GEMINI), prompt)
        assertTrue(call.url.path.endsWith("/models/test-model:generateContent"))
        assertNull(call.url.query)
        assertEquals("test-secret", call.headers["x-goog-api-key"])
        assertEquals("application/json", call.body.getJSONObject("generationConfig").getString("responseMimeType"))
        val response = JSONObject().put("candidates", JSONArray().put(JSONObject().put("finishReason", "STOP")
            .put("content", JSONObject().put("parts", JSONArray()
                .put(JSONObject().put("thought", true).put("text", "private reasoning"))
                .put(JSONObject().put("text", "result"))))))
        assertEquals("result", GeminiProvider.read(response))
    }

    @Test fun ollamaDisablesStreamingAndRequestsJson() {
        val call = OllamaProvider.build(settings(ProviderKind.OLLAMA).copy(apiKey = ""), prompt)
        assertEquals("/api/chat", call.url.path)
        assertFalse(call.body.getBoolean("stream"))
        assertEquals("json", call.body.getString("format"))
        assertTrue(call.headers.isEmpty())
        assertEquals("result", OllamaProvider.read(JSONObject().put("message", JSONObject().put("content", "result"))))
    }

    @Test fun promptContainsOnlyExplicitRequestAndChosenMemory() {
        val composed = PromptComposer.compose(AiSettings(personaId = "professional", customPrompt = "用简体中文", intimacy = 75),
            ReplyRequest(text = "延期说明", task = ReplyTask.EMAIL, contactMemory = "对方偏好清晰时间表", targetLanguage = "英语"))
        assertTrue(composed.system.contains("75/100"))
        assertTrue(composed.system.contains("用简体中文"))
        val user = JSONObject(composed.user)
        assertEquals("email", user.getString("task"))
        assertEquals("对方偏好清晰时间表", user.getString("contact_memory"))
        assertEquals("", user.getString("context"))
        assertEquals("英语", user.getString("target_language"))
    }

    @Test fun acceptsFullLengthMemoryWithContactNameMetadata() {
        val notes = "记".repeat(AiLimits.MEMORY)
        val name = "人".repeat(80)
        val composed = PromptComposer.compose(AiSettings(), ReplyRequest("你好", contactMemory = "联系人：$name\n$notes"))
        assertEquals("联系人：$name\n$notes", JSONObject(composed.user).getString("contact_memory"))
    }
}
