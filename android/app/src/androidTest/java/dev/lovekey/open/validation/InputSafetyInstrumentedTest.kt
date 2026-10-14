package dev.lovekey.open.validation

import android.text.InputType
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lovekey.open.ime.InputSafety
import dev.lovekey.open.ime.EditorSessionGuard
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InputSafetyInstrumentedTest {
    @Test fun allPasswordVariationsRemainSensitiveWithAdditionalFlags() {
        val textPasswords = listOf(InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)
        textPasswords.forEach { variation ->
            assertTrue("Text password variation $variation must block AI context", InputSafety.isSensitive(InputType.TYPE_CLASS_TEXT or variation))
            assertTrue("Suggestion flags must not weaken password protection", InputSafety.isSensitive(InputType.TYPE_CLASS_TEXT or variation or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_FLAG_MULTI_LINE))
        }
        assertTrue(InputSafety.isSensitive(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD))
        assertTrue(InputSafety.isSensitive(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD or InputType.TYPE_NUMBER_FLAG_SIGNED))
    }

    @Test fun ordinaryEditorClassesRemainAvailable() {
        listOf(
            InputType.TYPE_CLASS_TEXT,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE,
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL,
            InputType.TYPE_CLASS_PHONE,
        ).forEach { type -> assertFalse("Ordinary input type $type should stay usable", InputSafety.isSensitive(type)) }
    }

    @Test fun editorSwitchCancellationAndNewRequestsInvalidateOldResults() {
        val guard = EditorSessionGuard()
        guard.beginSession()
        val first = guard.startGeneration()
        assertTrue(guard.isCurrent(first))
        val second = guard.startGeneration()
        assertFalse("A late first reply must not replace the latest request", guard.isCurrent(first))
        assertTrue(guard.isCurrent(second))
        guard.cancelGeneration()
        assertFalse("A cancelled reply must not reappear", guard.isCurrent(second))
        val beforeSwitch = guard.startGeneration()
        guard.endSession()
        assertFalse("Replies must not leak after the editor closes", guard.isCurrent(beforeSwitch))
        guard.beginSession()
        assertFalse("The previous editor's reply must not enter a new editor", guard.isCurrent(beforeSwitch))
        assertTrue(guard.isCurrent(guard.startGeneration()))
    }

    @Test fun deletingAnEmojiRemovesOneUnicodeCodePoint() {
        assertEquals("你好", InputSafety.dropLastCodePoint("你好😀"))
        assertEquals("", InputSafety.dropLastCodePoint("😀"))
        assertEquals("", InputSafety.dropLastCodePoint(""))
    }
}
