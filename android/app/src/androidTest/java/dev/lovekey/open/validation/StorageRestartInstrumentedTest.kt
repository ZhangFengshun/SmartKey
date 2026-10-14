package dev.lovekey.open.validation

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.lovekey.open.ai.AiSettings
import dev.lovekey.open.ai.ProviderKind
import dev.lovekey.open.data.ContactMemory
import dev.lovekey.open.data.SettingsRepository
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Run this class in two separate instrumentation processes: storagePhase=seed then verify. */
@RunWith(AndroidJUnit4::class)
class StorageRestartInstrumentedTest {
    @Test fun decryptsSettingsAndMemoryAcrossInstrumentationProcesses() {
        val phase = InstrumentationRegistry.getArguments().getString("storagePhase")
        assumeTrue("This check needs the explicit seed/verify process sequence", phase == "seed" || phase == "verify")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences(PRIVATE_SETTINGS, Context.MODE_PRIVATE)
        val backup = context.getSharedPreferences("validation_encrypted_storage_backup", Context.MODE_PRIVATE)
        val marker = context.getSharedPreferences("validation_storage_phase", Context.MODE_PRIVATE)
        val repository = SettingsRepository(context)
        val settings = AiSettings(provider = ProviderKind.OLLAMA, baseUrl = "http://127.0.0.1:8765", model = "restart-validation-model", apiKey = SYNTHETIC_KEY, personaId = "direct", intimacy = 37)
        val memory = ContactMemory(id = "restart-validation", name = "重启测试联系人", notes = "重启后仍可读取的合成记忆")

        if (phase == "seed") {
            assertFalse("Finish the pending verify phase before seeding again", marker.getBoolean("pending", false))
            restorePreferences(backup, preferences.all.toMap())
            assertTrue(marker.edit().putBoolean("pending", true).commit())
            try {
                assertTrue(preferences.edit().clear().commit())
                repository.save(settings)
                repository.upsertMemory(memory)
                assertEquals(settings, repository.load())
            } catch (failure: Throwable) {
                restorePreferences(preferences, backup.all.toMap())
                backup.edit().clear().commit()
                marker.edit().clear().commit()
                throw failure
            }
        } else {
            assertTrue("Seed phase must run in an earlier instrumentation process", marker.getBoolean("pending", false))
            try {
                assertEquals(settings, repository.load())
                assertEquals(listOf(memory), repository.listMemories())
            } finally {
                // Preserve any settings that existed before this explicit validation.
                restorePreferences(preferences, backup.all.toMap())
                assertTrue(backup.edit().clear().commit())
                assertTrue(marker.edit().clear().commit())
            }
        }
    }
}
