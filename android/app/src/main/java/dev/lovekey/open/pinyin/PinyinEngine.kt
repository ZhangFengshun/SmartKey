package dev.lovekey.open.pinyin

data class PinyinCandidate(val index: Int, val text: String)

interface PinyinEngine {
    val ready: Boolean
    val raw: String
    val composing: String
    val candidates: List<PinyinCandidate>
    fun input(letter: Char): Boolean
    fun backspace()
    /** Returns text when the whole composition is chosen; null means a partial selection. */
    fun choose(index: Int): String?
    fun commitBest(): String?
    fun reset()
    fun close()
}
