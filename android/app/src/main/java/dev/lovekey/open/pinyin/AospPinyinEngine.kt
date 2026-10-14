package dev.lovekey.open.pinyin

import android.content.Context
import java.io.File

/** Offline sentence decoding backed by AOSP's dictionary, rather than a fixed word map. */
class AospPinyinEngine(context: Context) : PinyinEngine {
    override var ready: Boolean = false
        private set
    override var raw: String = ""
        private set
    override var composing: String = ""
        private set
    override var candidates: List<PinyinCandidate> = emptyList()
        private set
    private var count = 0
    private val selections = mutableListOf<PinyinSelection>()

    init {
        try {
            val dictionary = synchronized(DICTIONARY_LOCK) {
                val directory = File(context.filesDir, "pinyin").apply { mkdirs() }
                val file = File(directory, "system-49aebad.dat")
                if (!file.exists() || file.length() != DICTIONARY_BYTES) {
                    val temporary = File(directory, "dictionary.tmp")
                    context.assets.open("pinyin/dict_pinyin.dat").use { input ->
                        temporary.outputStream().use { input.copyTo(it) }
                    }
                    check(temporary.length() == DICTIONARY_BYTES)
                    check(temporary.renameTo(file))
                }
                file
            }
            ready = NativePinyin.acquire(this, dictionary.absolutePath)
        } catch (_: Exception) {
            ready = false
        } catch (_: LinkageError) {
            ready = false
        }
    }

    @Synchronized override fun input(letter: Char): Boolean {
        if (!ready || raw.length >= MAX_INPUT || !(letter in 'a'..'z' || letter == '\'')) return false
        NativePinyin.useSession(this, raw, selections) {
            raw += letter
            count = NativePinyin.search(raw)
            refresh()
        }
        return true
    }

    @Synchronized override fun backspace() {
        if (!ready || raw.isEmpty()) return
        NativePinyin.useSession(this, raw, selections) {
            raw = raw.dropLast(1)
            // Rebuild after deletion so a selected prefix never survives an edit.
            selections.clear()
            NativePinyin.reset()
            count = if (raw.isEmpty()) 0 else NativePinyin.search(raw)
            refresh()
        }
    }

    @Synchronized override fun choose(index: Int): String? {
        if (!ready || raw.isEmpty() || index !in 0 until count) return null
        return NativePinyin.useSession(this, raw, selections) {
            count = NativePinyin.choose(index)
            selections.add(PinyinSelection(raw, index))
            val starts = NativePinyin.spellingStarts()
            val fixed = NativePinyin.fixedLength()
            val complete = starts.isNotEmpty() && fixed == starts.size - 1 && NativePinyin.decodedLength() == raw.length
            if (complete) {
                val result = NativePinyin.candidate(0)
                reset()
                result.takeIf { it.isNotEmpty() }
            } else {
                refresh()
                null
            }
        }
    }

    @Synchronized override fun commitBest(): String? {
        if (raw.isEmpty()) return null
        val result = if (ready && count > 0) {
            NativePinyin.useSession(this, raw, selections) {
                val decoded = NativePinyin.decodedLength().coerceIn(0, raw.length)
                NativePinyin.candidate(0) + raw.substring(decoded)
            }
        } else raw
        reset()
        return result.takeIf { it.isNotEmpty() }
    }

    @Synchronized override fun reset() {
        if (ready) NativePinyin.discardSession(this)
        clearLocalState()
    }

    private fun clearLocalState() {
        raw = ""
        composing = ""
        candidates = emptyList()
        count = 0
        selections.clear()
    }

    @Synchronized override fun close() {
        // Closing a client must not reset another client's live native session.
        clearLocalState()
        if (ready) NativePinyin.release(this)
        ready = false
    }

    private fun refresh() {
        if (raw.isEmpty()) {
            composing = ""
            candidates = emptyList()
            return
        }
        val fixed = NativePinyin.fixedLength()
        val starts = NativePinyin.spellingStarts()
        val full = if (count > 0) NativePinyin.candidate(0) else ""
        val start = starts.getOrNull(fixed)?.coerceIn(0, raw.length) ?: 0
        composing = full.take(fixed) + raw.substring(start)
        candidates = (0 until count.coerceAtMost(60)).mapNotNull { index ->
            val text = NativePinyin.candidate(index).let { if (index == 0) it.drop(fixed) else it }
            text.takeIf { it.isNotEmpty() }?.let { PinyinCandidate(index, it) }
        }
    }

    companion object {
        const val MAX_INPUT = 39
        private const val DICTIONARY_BYTES = 1_068_442L
        private val DICTIONARY_LOCK = Any()
    }
}

internal data class PinyinSelection(val raw: String, val index: Int)

internal object NativePinyin {
    init { System.loadLibrary("smartkey_pinyin") }
    private val clients = mutableSetOf<Any>()
    private var owner: Any? = null
    /** AOSP has one native decoder, so lifetime and logical sessions both need isolation. */
    @Synchronized fun acquire(client: Any, dictionary: String): Boolean {
        if (clients.isEmpty() && !open(dictionary)) return false
        clients.add(client)
        return true
    }
    @Synchronized fun release(client: Any) {
        if (!clients.remove(client)) return
        if (owner === client) owner = null
        if (clients.isEmpty()) { close(); owner = null }
    }
    @Synchronized fun discardSession(client: Any) {
        if (owner === client) { reset(); owner = null }
    }
    @Synchronized fun <T> useSession(client: Any, raw: String, selections: List<PinyinSelection>, operation: () -> T): T {
        check(client in clients)
        if (owner !== client) {
            reset()
            // Restore each partial choice at the exact spelling where it was
            // made, then continue with any subsequently typed letters.
            selections.forEach { selected -> search(selected.raw); choose(selected.index) }
            if (raw.isNotEmpty() && (selections.isEmpty() || selections.last().raw != raw)) search(raw)
            owner = client
        }
        return operation()
    }
    external fun open(dictionary: String): Boolean
    external fun search(raw: String): Int
    external fun candidate(index: Int): String
    external fun choose(index: Int): Int
    external fun fixedLength(): Int
    external fun decodedLength(): Int
    external fun spellingStarts(): IntArray
    external fun reset()
    external fun close()
}
