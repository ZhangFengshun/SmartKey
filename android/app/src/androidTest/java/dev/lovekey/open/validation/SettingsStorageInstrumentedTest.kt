package dev.lovekey.open.validation

import android.content.Context
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.lovekey.open.ai.AiException
import dev.lovekey.open.ai.AiSettings
import dev.lovekey.open.ai.ProviderKind
import dev.lovekey.open.data.ContactMemory
import dev.lovekey.open.data.SettingsRepository
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

internal const val PRIVATE_SETTINGS = "private_ai_settings"
internal const val SYNTHETIC_KEY = "validation-only-key-71d0ab-no-real-credential"

internal fun restorePreferences(preferences: SharedPreferences, snapshot: Map<String, *>) {
    val editor = preferences.edit().clear()
    snapshot.forEach { (key, value) ->
        when (value) {
            is String -> editor.putString(key, value)
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
            is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
        }
    }
    check(editor.commit()) { "Could not restore validation preference snapshot" }
}

@RunWith(AndroidJUnit4::class)
class SettingsStorageInstrumentedTest {
    private lateinit var context: Context
    private lateinit var preferences: SharedPreferences
    private lateinit var repository: SettingsRepository
    private lateinit var original: Map<String, *>

    @Before fun prepare() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        preferences = context.getSharedPreferences(PRIVATE_SETTINGS, Context.MODE_PRIVATE)
        original = preferences.all.toMap()
        assertTrue(preferences.edit().clear().commit())
        repository = SettingsRepository(context)
    }

    @After fun restore() {
        restorePreferences(preferences, original)
    }

    @Test fun settingsSurviveNewRepositoryAndAreEncryptedOnDisk() {
        val settings = AiSettings(
            provider = ProviderKind.ANTHROPIC,
            baseUrl = "http://127.0.0.1:8765",
            model = "validation-model",
            apiKey = SYNTHETIC_KEY,
            personaId = "professional",
            customPrompt = "synthetic-private-prompt-71d0ab",
            style = "准确简洁",
            intimacy = 73,
            demoMode = false,
        )
        repository.save(settings)
        assertEquals(settings, SettingsRepository(context).load())
        val encrypted = preferences.getString("settings_v1", null)!!
        assertFalse("Credential must not be plaintext in preferences", encrypted.contains(SYNTHETIC_KEY))
        assertFalse("Prompt must not be plaintext in preferences", encrypted.contains(settings.customPrompt))
        val envelope = JSONObject(encrypted)
        assertEquals(1, envelope.getInt("version"))
        assertTrue(envelope.getString("iv").isNotBlank())
        assertTrue(envelope.getString("data").isNotBlank())
        val file = File(context.applicationInfo.dataDir, "shared_prefs/$PRIVATE_SETTINGS.xml")
        assertTrue("Committed settings must have a backing file", file.exists())
        val stored = file.readText()
        assertFalse("Credential must not appear in the settings XML", stored.contains(SYNTHETIC_KEY))
        assertFalse("Prompt must not appear in the settings XML", stored.contains(settings.customPrompt))
        assertFalse("Settings diagnostics must redact the key", settings.toString().contains(SYNTHETIC_KEY))

        repository.save(settings)
        assertNotEquals("Repeated encryption must use a fresh nonce", encrypted, preferences.getString("settings_v1", null))
        assertEquals(settings, SettingsRepository(context).load())
    }

    @Test fun contactMemoryCreateUpdateDeletePersistsWithoutPlaintext() {
        val first = ContactMemory(id = "validation-contact-1", name = "测试朋友甲", notes = "synthetic-private-memory-71d0ab")
        val second = ContactMemory(id = "validation-contact-2", name = "测试朋友乙", notes = "周末喜欢散步")
        repository.upsertMemory(first)
        repository.upsertMemory(second)
        assertEquals(listOf(first, second), SettingsRepository(context).listMemories())

        val updated = first.copy(name = "测试朋友甲更新", notes = "偏好直接表达，不用夸张措辞")
        repository.upsertMemory(updated)
        assertEquals(listOf(updated, second), SettingsRepository(context).listMemories())
        val stored = preferences.getString("memories_v1", null)!!
        assertFalse("Memory names must be encrypted", stored.contains(updated.name))
        assertFalse("Memory notes must be encrypted", stored.contains(updated.notes))

        repository.deleteMemory(first.id)
        assertEquals(listOf(second), SettingsRepository(context).listMemories())
        repository.clearMemories()
        assertTrue(SettingsRepository(context).listMemories().isEmpty())
    }

    @Test fun damagedCiphertextAndWrongStoragePurposeFailClosed() {
        repository.save(AiSettings(model = "validation-model", apiKey = SYNTHETIC_KEY))
        assertTrue(preferences.edit().putString("settings_v1", "{broken ciphertext").commit())
        expectAiFailure { SettingsRepository(context).load() }
        expectAiFailure { repository.save(AiSettings()) }
        assertEquals("Saving defaults must not destroy unreadable settings", "{broken ciphertext", preferences.getString("settings_v1", null))

        repository.upsertMemory(ContactMemory(id = "purpose-test", name = "测试", notes = "purpose-bound ciphertext"))
        val memoryCiphertext = preferences.getString("memories_v1", null)!!
        assertTrue(preferences.edit().putString("settings_v1", memoryCiphertext).commit())
        expectAiFailure { SettingsRepository(context).load() }
        repository.resetSettings()
        assertEquals(AiSettings(), SettingsRepository(context).load())
        assertEquals(1, repository.listMemories().size)
    }

    @Test fun invalidSettingsAndBlankContactDoNotOverwriteSavedData() {
        val settings = AiSettings(model = "valid-model", apiKey = SYNTHETIC_KEY)
        repository.save(settings)
        expectAiFailure { repository.save(settings.copy(apiKey = "invalid\nkey")) }
        expectAiFailure { repository.save(settings.copy(intimacy = 101)) }
        expectAiFailure { repository.upsertMemory(ContactMemory(name = "  ", notes = "unused")) }
        assertEquals(settings, SettingsRepository(context).load())
        assertTrue(repository.listMemories().isEmpty())
    }

    private fun expectAiFailure(action: () -> Unit) {
        try {
            action()
            fail("Invalid or unreadable storage must report an AiException")
        } catch (expected: AiException) {
            assertFalse("Error text must not disclose the synthetic key", expected.message.orEmpty().contains(SYNTHETIC_KEY))
        }
    }
}
