package dev.lovekey.open.ui

import java.util.UUID

/** One-use in-memory permission result. Discarded after process death. */
internal object GalleryPermissionBridge {
    private var pending: Pair<String, (Boolean, String?) -> Unit>? = null
    @Synchronized fun begin(callback: (Boolean, String?) -> Unit): String =
        UUID.randomUUID().toString().also { pending = it to callback }
    @Synchronized fun exists(id: String): Boolean = pending?.first == id
    @Synchronized fun cancel(id: String?) { if (pending?.first == id) pending = null }
    fun complete(id: String, granted: Boolean, error: String?) {
        val callback = synchronized(this) {
            if (pending?.first != id) return
            pending!!.second.also { pending = null }
        }
        callback(granted, error)
    }
}
