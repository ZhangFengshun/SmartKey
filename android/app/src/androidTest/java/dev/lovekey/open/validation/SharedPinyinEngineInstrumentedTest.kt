package dev.lovekey.open.validation

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.lovekey.open.pinyin.AospPinyinEngine
import dev.lovekey.open.pinyin.PinyinEngine
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Two real JNI clients exercise the process-global decoder's logical-session isolation. */
@RunWith(AndroidJUnit4::class)
class SharedPinyinEngineInstrumentedTest {
    @Test fun closingAnUnusedSecondClientDoesNotClearTheFirstComposition() = withTwoEngines { first, second ->
        type(first, "nihao")
        val hello = first.candidates.first { it.text == "你好" }
        val before = first.composing
        second.close()
        assertTrue("The surviving client must retain its native lifetime", first.ready)
        assertEquals(before, first.composing)
        assertEquals("Closing another client must not reset the live decoder", "你好", first.choose(hello.index))
    }

    @Test fun alternatingClientsRestoreTheirOwnSpellingAndCandidateIndices() = withTwoEngines { first, second ->
        val firstText = "nihao"
        val secondText = "zhongguo"
        repeat(secondText.length) { index ->
            if (index < firstText.length) assertTrue(first.input(firstText[index]))
            assertTrue(second.input(secondText[index]))
        }
        val hello = first.candidates.first { it.text == "你好" }
        val china = second.candidates.first { it.text == "中国" }
        assertEquals("Each choice must bind its own restored native session", "你好", first.choose(hello.index))
        assertEquals("A choice/reset in the other client must not change this choice", "中国", second.choose(china.index))
    }

    @Test fun partialChoiceSurvivesAnotherClientsInputAndClose() = withTwoEngines { first, second ->
        type(first, "woaini")
        val prefix = first.candidates.first { it.text == "我" }
        assertNull(first.choose(prefix.index))
        val remaining = first.candidates.first()
        assertTrue(first.composing.startsWith("我"))
        type(second, "shi")
        second.close()
        assertEquals("A restored session must replay the selected prefix, not just raw pinyin",
            "我" + remaining.text, first.choose(remaining.index))
    }

    @Test fun resettingAnInactiveClientDoesNotClearAnothersFixedPrefix() = withTwoEngines { first, second ->
        type(first, "nihao")
        type(second, "woaini")
        val prefix = second.candidates.first { it.text == "我" }
        assertNull(second.choose(prefix.index))
        val remaining = second.candidates.first()
        first.reset()
        assertEquals("The active client's partial selection must remain intact", "我" + remaining.text, second.choose(remaining.index))
    }

    private fun withTwoEngines(block: (PinyinEngine, PinyinEngine) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val first = AospPinyinEngine(context)
        val second = AospPinyinEngine(context)
        try {
            assertTrue(first.ready)
            assertTrue(second.ready)
            block(first, second)
        } finally {
            second.close()
            first.close()
        }
    }

    private fun type(engine: PinyinEngine, text: String) { text.forEach { assertTrue(engine.input(it)) } }
}
