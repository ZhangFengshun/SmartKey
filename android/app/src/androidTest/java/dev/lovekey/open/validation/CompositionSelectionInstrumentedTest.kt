package dev.lovekey.open.validation

import android.text.InputType
import android.view.ContextThemeWrapper
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.widget.EditText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.lovekey.open.ime.CompositionSelectionGuard
import dev.lovekey.open.pinyin.AospPinyinEngine
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Uses a real EditText InputConnection and packaged JNI, without fake cursor positions. */
@RunWith(AndroidJUnit4::class)
class CompositionSelectionInstrumentedTest {
    @Test fun cursorMoveThenImmediateNextKeyStartsANewComposition() = onMain {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val editor = EditText(ContextThemeWrapper(context, android.R.style.Theme_Material_Light)).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setText(""); setSelection(0)
        }
        val connection = editor.onCreateInputConnection(EditorInfo())!!
        val engine = AospPinyinEngine(context)
        val guard = CompositionSelectionGuard()
        try {
            assertTrue(engine.ready)
            assertTrue(engine.input('n')); assertTrue(engine.input('i'))
            assertTrue(connection.setComposingText(engine.composing, 1))
            guard.recordWrite(selection(connection))
            val queuedCallbackRevision = guard.revision
            assertEquals("ni", editor.text.toString())

            // The next key is pressed before any deferred selection check.
            editor.setSelection(0)
            assertTrue("The pre-key checkpoint must notice a real cursor move", guard.hasMoved(selection(connection)))
            engine.reset(); guard.clear(); connection.finishComposingText()
            assertTrue(engine.input('h'))
            assertTrue(connection.setComposingText(engine.composing, 1))
            guard.recordWrite(selection(connection))

            assertEquals("The previous raw spelling must not be carried to the new cursor", "h", engine.raw)
            assertEquals("The old editor text must remain where the user left it", "hni", editor.text.toString())
            assertFalse(guard.hasMoved(selection(connection)))
            assertTrue("An older deferred check must now be obsolete", queuedCallbackRevision != guard.revision)
        } finally { engine.close() }
    }

    @Test fun oldCallbackCoordinatesDoNotOverrideLatestOwnWriteCheckpoint() = onMain {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val editor = EditText(ContextThemeWrapper(context, android.R.style.Theme_Material_Light)).apply {
            inputType = InputType.TYPE_CLASS_TEXT; setText(""); setSelection(0)
        }
        val connection = editor.onCreateInputConnection(EditorInfo())!!
        val guard = CompositionSelectionGuard()
        assertTrue(connection.setComposingText("n", 1))
        guard.recordWrite(selection(connection))
        val oldRevision = guard.revision
        assertEquals(Pair(1, 1), selection(connection))
        assertFalse("Current editor state wins over a queued callback reporting cursor zero", guard.hasMoved(selection(connection)))
        assertTrue(connection.setComposingText("ni", 1))
        guard.recordWrite(selection(connection))
        assertEquals(Pair(2, 2), selection(connection))
        assertFalse("An old one-character callback must not clear the newer spelling", guard.hasMoved(selection(connection)))
        assertTrue(oldRevision != guard.revision)
        editor.setSelection(1)
        assertTrue("A genuine subsequent cursor move must still be detected", guard.hasMoved(selection(connection)))
        connection.finishComposingText()
    }

    private fun selection(connection: InputConnection): Pair<Int, Int> {
        val extracted = connection.getExtractedText(ExtractedTextRequest().apply { hintMaxChars = 160; hintMaxLines = 2 }, 0)!!
        assertTrue(extracted.selectionStart >= 0)
        return Pair(extracted.startOffset + extracted.selectionStart, extracted.startOffset + extracted.selectionEnd)
    }

    private fun onMain(block: () -> Unit) {
        var failure: Throwable? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { try { block() } catch (error: Throwable) { failure = error } }
        failure?.let { throw it }
    }
}
