package dev.lovekey.open.ui

import java.util.UUID

/** A one-use, in-process handoff. No text, images or URI are persisted. */
internal object KeyboardImageImport {
    private var request: Pair<String, (String?, String?) -> Unit>? = null

    @Synchronized fun begin(callback: (String?, String?) -> Unit): String {
        val id = UUID.randomUUID().toString()
        request = id to callback
        return id
    }

    @Synchronized fun exists(id: String): Boolean = request?.first == id

    @Synchronized fun cancel(id: String?) {
        if (request?.first == id) request = null
    }

    fun complete(id: String, text: String?, error: String?) {
        val callback = synchronized(this) {
            if (request?.first != id) return
            request!!.second.also { request = null }
        }
        callback(text, error)
    }
}
