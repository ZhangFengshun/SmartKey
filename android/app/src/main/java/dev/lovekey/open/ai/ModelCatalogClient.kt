package dev.lovekey.open.ai

import org.json.JSONObject
import java.util.Locale

data class AvailableModel(val id: String, val displayName: String = id)

class ModelCatalogClient internal constructor(private val transport: HttpTransport) {
    constructor() : this(UrlConnectionTransport())

    /** Blocking real catalog lookup. Model may be blank; demoMode does not substitute a fake catalog. */
    fun list(settings: AiSettings): List<AvailableModel> {
        val endpoint = EndpointPolicy.catalogEndpoint(settings)
        if (settings.apiKey.length > 8_192 || settings.apiKey.any { it.isISOControl() }) throw AiException("API Key 格式不正确，请重新填写。")
        val keyRequired = settings.provider == ProviderKind.ANTHROPIC || settings.provider == ProviderKind.GEMINI ||
            (settings.provider == ProviderKind.OPENAI_COMPATIBLE && !EndpointPolicy.isLocalHost(endpoint.host))
        if (keyRequired && settings.apiKey.isBlank()) throw AiException("请先填写 API Key，再获取模型列表。")
        val headers = when (settings.provider) {
            ProviderKind.ANTHROPIC -> mapOf("x-api-key" to settings.apiKey, "anthropic-version" to "2023-06-01")
            ProviderKind.GEMINI -> mapOf("x-goog-api-key" to settings.apiKey)
            else -> if (settings.apiKey.isBlank()) emptyMap() else mapOf("Authorization" to "Bearer ${settings.apiKey}")
        }
        val models = linkedMapOf<String, AvailableModel>()
        val cursors = mutableSetOf<String>()
        var cursor: String? = null
        try {
            repeat(MAX_PAGES) {
                if (Thread.currentThread().isInterrupted) throw AiException("请求已取消。")
                val response = transport.get(EndpointPolicy.catalogEndpoint(settings, cursor), headers)
                if (Thread.currentThread().isInterrupted) throw AiException("请求已取消。")
                readModels(settings.provider, response).forEach { model ->
                    if (models.size < MAX_MODELS) models.putIfAbsent(model.id, model)
                }
                val next = nextCursor(settings.provider, response)
                if (next == null || models.size >= MAX_MODELS) return finish(models.values.toList())
                if (!cursors.add(next)) throw AiException("模型服务重复返回同一分页，请手动填写模型或稍后重试。")
                cursor = next
            }
            return finish(models.values.toList())
        } catch (error: AiException) {
            throw error
        } catch (_: Exception) {
            throw AiException("模型列表格式无法识别，请检查接口类型，或手动填写模型名称。")
        }
    }

    internal fun readModels(kind: ProviderKind, response: JSONObject): List<AvailableModel> {
        val entries = response.getJSONArray(if (kind in listOf(ProviderKind.GEMINI, ProviderKind.OLLAMA)) "models" else "data")
        val models = linkedMapOf<String, AvailableModel>()
        for (index in 0 until entries.length()) {
            val item = entries.optJSONObject(index) ?: continue
            if (kind == ProviderKind.GEMINI) {
                val methods = item.optJSONArray("supportedGenerationMethods") ?: continue
                if ((0 until methods.length()).none { methods.optString(it) == "generateContent" }) continue
            }
            val id = when (kind) {
                ProviderKind.GEMINI -> string(item, "name").removePrefix("models/")
                ProviderKind.OLLAMA -> string(item, "model").ifBlank { string(item, "name") }
                else -> string(item, "id")
            }.trim()
            if (id.isBlank() || id.length > 160 || id.any { it.isISOControl() }) continue
            val name = when (kind) {
                ProviderKind.ANTHROPIC -> string(item, "display_name")
                ProviderKind.GEMINI -> string(item, "displayName")
                else -> id
            }.trim().takeIf { it.isNotBlank() && it.length <= 200 && it.none { character -> character.isISOControl() } } ?: id
            models.putIfAbsent(id, AvailableModel(id, name))
            if (models.size >= MAX_MODELS) break
        }
        return models.values.toList()
    }

    private fun nextCursor(kind: ProviderKind, response: JSONObject): String? = when (kind) {
        ProviderKind.ANTHROPIC -> if (response.optBoolean("has_more", false)) {
            string(response, "last_id").takeIf { it.isNotBlank() }
                ?: throw AiException("模型服务的分页信息不完整，请手动填写模型名称。")
        } else null
        ProviderKind.GEMINI -> string(response, "nextPageToken").takeIf { it.isNotBlank() }
        else -> null
    }

    private fun string(value: JSONObject, key: String): String = value.opt(key) as? String ?: ""
    private fun finish(models: List<AvailableModel>): List<AvailableModel> {
        if (models.isEmpty()) throw AiException("服务没有返回可用的文本生成模型，请检查权限或手动填写模型名称。")
        return models.sortedBy { it.id.lowercase(Locale.ROOT) }
    }

    companion object {
        const val MAX_PAGES = 5
        const val MAX_MODELS = 500
    }
}
