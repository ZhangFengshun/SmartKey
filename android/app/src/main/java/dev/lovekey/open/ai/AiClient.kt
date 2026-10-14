package dev.lovekey.open.ai

import org.json.JSONObject

class AiClient internal constructor(private val transport: HttpTransport) {
    constructor() : this(UrlConnectionTransport())

    /** Blocking call: invoke on a background executor, never from the UI thread. */
    fun generate(settings: AiSettings, request: ReplyRequest): List<ReplyCandidate> {
        PromptComposer.validate(settings, request)
        if (settings.demoMode) return DemoReplies.generate(request)
        if (settings.model.isBlank()) throw AiException("请先在「模型设置」中填写模型名称，或主动开启演示模式。")
        if (settings.model.length > 160 || settings.model.any { it.isISOControl() }) throw AiException("模型名称格式不正确。")
        if (settings.baseUrl.length > 2_048) throw AiException("模型服务地址过长。")
        if (settings.apiKey.length > 8_192 || settings.apiKey.any { it.isISOControl() }) throw AiException("API Key 格式不正确，请重新填写。")
        val endpoint = EndpointPolicy.endpoint(settings)
        val keyRequired = settings.provider == ProviderKind.ANTHROPIC || settings.provider == ProviderKind.GEMINI ||
            (settings.provider == ProviderKind.OPENAI_COMPATIBLE && !EndpointPolicy.isLocalHost(endpoint.host))
        if (keyRequired && settings.apiKey.isBlank()) throw AiException("请先在「模型设置」中填写 API Key。真实模式不会使用示例代替模型回复。")
        if (Thread.currentThread().isInterrupted) throw AiException("请求已取消。")
        try {
            val adapter = ProviderAdapters.forKind(settings.provider)
            val call = adapter.build(settings, PromptComposer.compose(settings, request))
            val response = transport.post(call)
            if (Thread.currentThread().isInterrupted) throw AiException("请求已取消。")
            return parseCandidates(adapter.read(response))
        } catch (error: AiException) {
            throw error
        } catch (_: Exception) {
            throw AiException("模型未返回有效的三个候选，请重试或更换支持 JSON 输出的模型。")
        }
    }

    internal fun parseCandidates(raw: String): List<ReplyCandidate> {
        val trimmed = raw.trim().replace(Regex("^```(?:json)?\\s*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*```$"), "").trim()
        val items = JSONObject(trimmed).getJSONArray("candidates")
        if (items.length() != 3) throw AiException("模型返回的候选数量不正确，请重试。")
        val candidates = (0 until items.length()).map { index ->
            val item = items.getJSONObject(index)
            val title = item.getString("title").trim()
            val text = item.getString("text").trim()
            if (title.isBlank() || title.length > 50 || text.isBlank() || text.length > AiLimits.CANDIDATE) {
                throw AiException("模型返回了空白或过长的候选，请重试。")
            }
            ReplyCandidate(title, text)
        }
        if (candidates.map { it.text }.distinct().size != 3) throw AiException("模型返回的候选重复，请重试。")
        return candidates
    }
}
