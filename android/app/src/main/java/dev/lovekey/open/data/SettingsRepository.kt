package dev.lovekey.open.data

import android.content.Context
import dev.lovekey.open.ai.AiException
import dev.lovekey.open.ai.AiLimits
import dev.lovekey.open.ai.AiSettings
import dev.lovekey.open.ai.Personas
import dev.lovekey.open.ai.ProviderKind
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class ContactMemory(val id: String = UUID.randomUUID().toString(), val name: String, val notes: String)

/** Only user-managed settings and contact notes are stored; requests and generated text are not saved. */
class SettingsRepository(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("private_ai_settings", Context.MODE_PRIVATE)
    private val secure = SecurePreferences()

    fun load(): AiSettings = synchronized(LOCK) {
        val encrypted = preferences.getString(SETTINGS, null) ?: return@synchronized AiSettings()
        try {
            val value = JSONObject(secure.decrypt(encrypted, SETTINGS))
            val provider = ProviderKind.entries.firstOrNull { it.name == value.optString("provider") }
                ?: ProviderKind.OPENAI_COMPATIBLE
            AiSettings(
                provider = provider,
                baseUrl = value.optString("baseUrl", provider.defaultBaseUrl),
                model = value.optString("model", ""),
                apiKey = value.optString("apiKey", ""),
                personaId = Personas.find(value.optString("personaId", "warm")).id,
                customPrompt = value.optString("customPrompt", ""),
                style = value.optString("style", "自然简洁"),
                intimacy = value.optInt("intimacy", 50).coerceIn(0, 100),
                demoMode = value.optBoolean("demoMode", false),
            )
        } catch (_: Exception) {
            throw AiException("无法解密本机设置，请重置模型设置后重新填写。")
        }
    }

    fun save(settings: AiSettings) = synchronized(LOCK) {
        // A UI recovery preview may contain default values. Do not overwrite unreadable
        // settings until the user explicitly chooses resetSettings().
        if (preferences.contains(SETTINGS)) load()
        if (settings.baseUrl.length > 2_048 || settings.model.length > 160 || settings.apiKey.length > 8_192 ||
            settings.customPrompt.length > AiLimits.CUSTOM_PROMPT || settings.style.length > 200) {
            throw AiException("设置内容过长，请缩短后保存。")
        }
        if (settings.intimacy !in 0..100) throw AiException("亲密度必须在 0 到 100 之间。")
        if (settings.apiKey.any { it.isISOControl() } || settings.model.any { it.isISOControl() }) {
            throw AiException("模型名称或 API Key 包含无效字符，请重新填写。")
        }
        val value = JSONObject().put("provider", settings.provider.name).put("baseUrl", settings.baseUrl.trim())
            .put("model", settings.model.trim()).put("apiKey", settings.apiKey.trim())
            .put("personaId", Personas.find(settings.personaId).id).put("customPrompt", settings.customPrompt)
            .put("style", settings.style).put("intimacy", settings.intimacy).put("demoMode", settings.demoMode)
        persist(SETTINGS, value.toString())
    }

    fun listMemories(): List<ContactMemory> = synchronized(LOCK) {
        val encrypted = preferences.getString(MEMORIES, null) ?: return@synchronized emptyList()
        try {
            val list = JSONArray(secure.decrypt(encrypted, MEMORIES))
            (0 until list.length()).map { index ->
                val value = list.getJSONObject(index)
                ContactMemory(value.getString("id"), value.getString("name"), value.getString("notes"))
            }
        } catch (_: Exception) {
            throw AiException("无法解密本机联系人记忆，请清除记忆后重新添加。")
        }
    }

    fun upsertMemory(memory: ContactMemory) = synchronized(LOCK) {
        if (memory.id.isBlank() || memory.id.length > 120 || memory.name.isBlank() || memory.name.length > 80 ||
            memory.notes.length > AiLimits.MEMORY) throw AiException("请填写联系人名称；名称限 80 字，记忆限 ${AiLimits.MEMORY} 字。")
        val existing = listMemories().toMutableList()
        val index = existing.indexOfFirst { it.id == memory.id }
        val clean = memory.copy(name = memory.name.trim(), notes = memory.notes.trim())
        if (index >= 0) existing[index] = clean else {
            if (existing.size >= 100) throw AiException("最多保存 100 位联系人，请先删除不再需要的记忆。")
            existing.add(clean)
        }
        saveMemories(existing)
    }

    fun deleteMemory(id: String) = synchronized(LOCK) {
        saveMemories(listMemories().filterNot { it.id == id })
    }

    fun resetSettings() = synchronized(LOCK) {
        if (!preferences.edit().remove(SETTINGS).commit()) throw AiException("无法重置设置，请重试。")
    }

    fun clearMemories() = synchronized(LOCK) {
        if (!preferences.edit().remove(MEMORIES).commit()) throw AiException("无法清除联系人记忆，请重试。")
    }

    private fun saveMemories(memories: List<ContactMemory>) {
        val values = JSONArray()
        memories.forEach { values.put(JSONObject().put("id", it.id).put("name", it.name).put("notes", it.notes)) }
        persist(MEMORIES, values.toString())
    }

    private fun persist(name: String, plaintext: String) {
        try {
            val encrypted = secure.encrypt(plaintext, name)
            if (encrypted.length > 2_097_152) throw AiException("本机保存内容过多，请缩短联系人记忆后重试。")
            if (!preferences.edit().putString(name, encrypted).commit()) throw AiException("无法保存本机设置，请重试。")
        } catch (error: AiException) {
            throw error
        } catch (_: Exception) {
            throw AiException("手机安全存储暂时不可用，请解锁手机后重试。")
        }
    }

    companion object {
        private val LOCK = Any()
        private const val SETTINGS = "settings_v1"
        private const val MEMORIES = "memories_v1"
    }
}
