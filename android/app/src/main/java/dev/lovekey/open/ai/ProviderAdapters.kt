package dev.lovekey.open.ai

import org.json.JSONArray
import org.json.JSONObject
import java.net.URL

internal data class ProviderCall(val url: URL, val headers: Map<String, String>, val body: JSONObject)

internal interface LlmProvider {
    fun build(settings: AiSettings, prompt: ComposedPrompt): ProviderCall
    fun read(response: JSONObject): String
}

internal object ProviderAdapters {
    fun forKind(kind: ProviderKind): LlmProvider = when (kind) {
        ProviderKind.OPENAI_COMPATIBLE -> OpenAiCompatibleProvider
        ProviderKind.ANTHROPIC -> AnthropicProvider
        ProviderKind.GEMINI -> GeminiProvider
        ProviderKind.OLLAMA -> OllamaProvider
    }
}

private fun messages(prompt: ComposedPrompt) = JSONArray()
    .put(JSONObject().put("role", "system").put("content", prompt.system))
    .put(JSONObject().put("role", "user").put("content", prompt.user))

private fun bearer(key: String): Map<String, String> =
    if (key.isBlank()) emptyMap() else mapOf("Authorization" to "Bearer $key")

internal object OpenAiCompatibleProvider : LlmProvider {
    override fun build(settings: AiSettings, prompt: ComposedPrompt) = ProviderCall(
        EndpointPolicy.endpoint(settings), bearer(settings.apiKey),
        JSONObject().put("model", settings.model).put("messages", messages(prompt)).put("stream", false).apply {
            val host = EndpointPolicy.endpoint(settings).host.lowercase()
            if (settings.model.contains("deepseek", ignoreCase = true) && settings.model.contains("v4", ignoreCase = true) &&
                host in setOf("api-inference.modelscope.cn", "api.deepseek.com")) {
                put("thinking", JSONObject().put("type", "disabled"))
                if (host == "api-inference.modelscope.cn") put("enable_thinking", false)
            }
        },
    )

    override fun read(response: JSONObject): String {
        val choice = response.getJSONArray("choices").getJSONObject(0)
        if (choice.optString("finish_reason") == "length") throw AiException("模型输出被截断，请重试或更换模型。")
        val content = choice.getJSONObject("message").get("content")
        return when (content) {
            is String -> content
            is JSONArray -> (0 until content.length()).mapNotNull { index ->
                content.optJSONObject(index)?.let { if (it.optString("type") == "text") it.optString("text") else null }
            }.joinToString("")
            else -> throw AiException("模型未返回可用的文本，请重试。")
        }
    }
}

internal object AnthropicProvider : LlmProvider {
    override fun build(settings: AiSettings, prompt: ComposedPrompt) = ProviderCall(
        EndpointPolicy.endpoint(settings),
        mapOf("x-api-key" to settings.apiKey, "anthropic-version" to "2023-06-01"),
        JSONObject().put("model", settings.model).put("max_tokens", 2_048).put("stream", false)
            .put("system", prompt.system)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt.user))),
    )

    override fun read(response: JSONObject): String {
        if (response.optString("stop_reason") == "max_tokens") throw AiException("模型输出被截断，请重试。")
        val content = response.getJSONArray("content")
        return (0 until content.length()).mapNotNull { index ->
            val item = content.getJSONObject(index)
            if (item.optString("type") == "text") item.optString("text") else null
        }.joinToString("")
    }
}

internal object GeminiProvider : LlmProvider {
    override fun build(settings: AiSettings, prompt: ComposedPrompt) = ProviderCall(
        EndpointPolicy.endpoint(settings), mapOf("x-goog-api-key" to settings.apiKey),
        JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt.system))))
            .put("contents", JSONArray().put(JSONObject().put("role", "user")
                .put("parts", JSONArray().put(JSONObject().put("text", prompt.user)))))
            .put("generationConfig", JSONObject().put("responseMimeType", "application/json").put("maxOutputTokens", 2_048)),
    )

    override fun read(response: JSONObject): String {
        val candidate = response.getJSONArray("candidates").getJSONObject(0)
        val reason = candidate.optString("finishReason")
        if (reason == "MAX_TOKENS") throw AiException("模型输出被截断，请重试。")
        if (reason.isNotBlank() && reason != "STOP") throw AiException("模型没有生成可用回复，请调整输入后重试。")
        val parts = candidate.getJSONObject("content").getJSONArray("parts")
        return (0 until parts.length()).mapNotNull { index ->
            val part = parts.getJSONObject(index)
            if (!part.optBoolean("thought", false) && part.has("text")) part.getString("text") else null
        }.joinToString("")
    }
}

internal object OllamaProvider : LlmProvider {
    override fun build(settings: AiSettings, prompt: ComposedPrompt) = ProviderCall(
        EndpointPolicy.endpoint(settings), bearer(settings.apiKey),
        JSONObject().put("model", settings.model).put("messages", messages(prompt))
            .put("stream", false).put("format", "json")
            .put("options", JSONObject().put("num_predict", 2_048)),
    )

    override fun read(response: JSONObject): String {
        if (response.optString("done_reason") == "length") throw AiException("模型输出被截断，请重试。")
        return response.getJSONObject("message").getString("content")
    }
}
