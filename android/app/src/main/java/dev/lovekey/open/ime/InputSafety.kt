package dev.lovekey.open.ime

import android.text.InputType

object InputSafety {
    fun isSensitive(inputType: Int): Boolean {
        val type = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        return (type == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD) ||
            (type == InputType.TYPE_CLASS_TEXT && variation in setOf(
                InputType.TYPE_TEXT_VARIATION_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            ))
    }

    fun dropLastCodePoint(text: String): String =
        if (text.isEmpty()) text else text.substring(0, text.offsetByCodePoints(text.length, -1))
}

/** Tokens must match both the editor session and the latest user request. */
class EditorSessionGuard {
    data class Token(val session: Long, val generation: Long)
    private var session = 0L
    private var generation = 0L
    private var active = false
    fun beginSession() { session++; generation++; active = true }
    fun endSession() { session++; generation++; active = false }
    fun cancelGeneration() { generation++ }
    fun startGeneration(): Token { generation++; return Token(session, generation) }
    fun isCurrent(token: Token): Boolean = active && token.session == session && token.generation == generation
    fun isSameSession(token: Token): Boolean = active && token.session == session
}

internal data class ReplyDraftSnapshot(val before: String?, val after: String?, val selection: String?, val cursor: Pair<Int, Int>? = null) {
    fun readable(): Boolean = before != null && after != null
    fun empty(): Boolean = readable() && before!!.isEmpty() && after!!.isEmpty() && selection.isNullOrEmpty()
}

internal object ReplyDraftGuard {
    fun canReplaceInserted(text: String, current: ReplyDraftSnapshot): Boolean =
        text.isNotEmpty() && current.selection.isNullOrEmpty() && current.before?.endsWith(text) == true
}

/** Own writes are checkpoints; callback coordinates alone never replace them. */
class CompositionSelectionGuard {
    var revision: Long = 0
        private set
    private var ownSelection: Pair<Int, Int>? = null
    fun recordWrite(selection: Pair<Int, Int>?) { revision++; ownSelection = selection }
    fun clear() { revision++; ownSelection = null }
    fun hasMoved(actual: Pair<Int, Int>?): Boolean =
        ownSelection != null && actual != null && actual != ownSelection
}
