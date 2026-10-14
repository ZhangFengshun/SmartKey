package dev.lovekey.open.ime

import org.junit.Assert.*
import org.junit.Test

class ReplyDraftGuardTest {
    @Test fun candidateSwitchCanReplaceOnlyItsOwnDraft() {
        assertTrue(ReplyDraftGuard.canReplaceInserted("候选一", ReplyDraftSnapshot("原有前缀候选一", "后缀", "")))
        assertFalse(ReplyDraftGuard.canReplaceInserted("候选一", ReplyDraftSnapshot("候选一已修改", "", "")))
        assertFalse(ReplyDraftGuard.canReplaceInserted("候选一", ReplyDraftSnapshot("候选一", "", "选中的其他文字")))
    }

    @Test fun missingEditorTextDoesNotAllowReplacement() {
        assertFalse(ReplyDraftGuard.canReplaceInserted("候选", ReplyDraftSnapshot(null, null, null)))
        assertFalse(ReplyDraftGuard.canReplaceInserted("", ReplyDraftSnapshot("", "", "")))
    }

    @Test fun regeneratedDraftMustStillBelongToSameEditorSession() {
        val guard = EditorSessionGuard()
        guard.beginSession()
        val first = guard.startGeneration()
        guard.cancelGeneration()
        assertTrue(guard.isSameSession(first))
        assertFalse(guard.isCurrent(first))
        guard.beginSession()
        assertFalse(guard.isSameSession(first))
    }
}
