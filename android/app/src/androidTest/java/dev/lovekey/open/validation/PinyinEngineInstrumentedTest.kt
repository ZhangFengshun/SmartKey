package dev.lovekey.open.validation

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.lovekey.open.pinyin.AospPinyinEngine
import dev.lovekey.open.pinyin.PinyinEngine
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the packaged JNI decoder and dictionary, never a fake or fixed map. */
@RunWith(AndroidJUnit4::class)
class PinyinEngineInstrumentedTest {
    private lateinit var engine: PinyinEngine

    @Before fun openDictionary() {
        engine = AospPinyinEngine(InstrumentationRegistry.getInstrumentation().targetContext)
        assertTrue("Packaged AOSP native decoder and dictionary must load", engine.ready)
    }

    @After fun releaseDecoder() { engine.close() }

    @Test fun commonWordsAndUnlistedSentenceDecodeOffline() {
        type("nihao")
        val hello = engine.candidates.firstOrNull { it.text == "你好" }
        assertNotNull("The real lexicon must offer 你好", hello)
        assertEquals("你好", engine.choose(hello!!.index))
        assertEquals("", engine.raw)

        type("zhongguo")
        assertTrue("The real lexicon must offer 中国", engine.candidates.any { it.text == "中国" })
        engine.reset()

        type("jintianxiayule")
        val sentence = engine.commitBest()
        assertNotNull("Sentence decoding should return Chinese text", sentence)
        assertTrue("The decoder should compose multiple words", sentence!!.length >= 4)
        assertFalse("Valid pinyin should not remain in the completed sentence", sentence.any { it in 'a'..'z' })
        assertTrue(engine.candidates.isEmpty())
    }

    @Test fun homophonesOfferAlternativeSelections() {
        type("shi")
        val distinct = engine.candidates.distinctBy { it.text }
        assertTrue("A syllable must offer several dictionary candidates", distinct.size >= 3)
        val alternative = distinct.first { it.index != 0 && it.text.length == 1 }
        assertEquals("Choosing an alternative must commit its actual text", alternative.text, engine.choose(alternative.index))
        assertEquals("", engine.raw)
        assertEquals("", engine.composing)
    }

    @Test fun partialSelectionContinuesAndDeletionRebuildsComposition() {
        type("woaini")
        val prefix = engine.candidates.firstOrNull { it.text == "我" }
        assertNotNull("Multi-syllable input must expose selectable prefix words", prefix)
        assertNull("Choosing a prefix should retain the remaining composition", engine.choose(prefix!!.index))
        assertTrue("Selected prefix should be visible", engine.composing.startsWith("我"))
        assertTrue("Remaining syllables should still have candidates", engine.candidates.isNotEmpty())
        engine.backspace()
        assertEquals("woain", engine.raw)
        assertEquals("Editing a partly selected phrase should clear its fixed prefix", "woain", engine.composing)
        assertTrue(engine.input('i'))
        val chosenAgain = engine.candidates.first { it.text == "我" }
        assertNull(engine.choose(chosenAgain.index))
        val completed = engine.commitBest()
        assertTrue("Committing the remainder must retain the selected prefix", completed.orEmpty().startsWith("我"))

        type("nihao")
        engine.backspace()
        assertEquals("niha", engine.raw)
        assertEquals("The visible spelling must reflect the deleted letter", "niha", engine.composing)
        assertTrue(engine.input('o'))
        assertTrue("Re-entering a letter should restore dictionary candidates", engine.candidates.any { it.text == "你好" })
        engine.reset()
        assertEquals("", engine.raw)
        assertEquals("", engine.composing)
        assertTrue(engine.candidates.isEmpty())
        assertNull(engine.commitBest())
    }

    @Test fun invalidInputAndCloseLeaveNoStaleComposition() {
        assertFalse(engine.input('1'))
        assertFalse(engine.input('你'))
        assertEquals("", engine.raw)
        type("ni")
        assertNull(engine.choose(-1))
        assertEquals("ni", engine.raw)
        engine.close()
        assertFalse(engine.ready)
        assertEquals("", engine.raw)
        assertTrue(engine.candidates.isEmpty())
        assertFalse(engine.input('n'))
    }

    private fun type(pinyin: String) {
        pinyin.forEach { assertTrue("Valid syllable character must be accepted", engine.input(it)) }
        assertEquals(pinyin, engine.raw)
        assertTrue("Valid pinyin must produce dictionary candidates", engine.candidates.isNotEmpty())
    }
}
