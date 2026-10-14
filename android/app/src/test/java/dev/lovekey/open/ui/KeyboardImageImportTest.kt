package dev.lovekey.open.ui

import org.junit.Assert.*
import org.junit.Test

class KeyboardImageImportTest {
    @Test fun resultIsDeliveredOnlyOnce() {
        var calls = 0
        val id = KeyboardImageImport.begin { text, error ->
            assertEquals("fixture", text)
            assertNull(error)
            calls++
        }
        KeyboardImageImport.complete(id, "fixture", null)
        KeyboardImageImport.complete(id, "duplicate", null)
        assertEquals(1, calls)
        assertFalse(KeyboardImageImport.exists(id))
    }

    @Test fun oldPickerCannotReplaceNewRequest() {
        var oldCalls = 0
        var newCalls = 0
        val old = KeyboardImageImport.begin { _, _ -> oldCalls++ }
        val next = KeyboardImageImport.begin { _, _ -> newCalls++ }
        KeyboardImageImport.complete(old, "late", null)
        assertTrue(KeyboardImageImport.exists(next))
        KeyboardImageImport.complete(next, "current", null)
        assertEquals(0, oldCalls)
        assertEquals(1, newCalls)
    }

    @Test fun destroyedKeyboardDiscardsPickerResult() {
        var calls = 0
        val id = KeyboardImageImport.begin { _, _ -> calls++ }
        KeyboardImageImport.cancel(id)
        KeyboardImageImport.complete(id, "late", null)
        assertEquals(0, calls)
    }
}
