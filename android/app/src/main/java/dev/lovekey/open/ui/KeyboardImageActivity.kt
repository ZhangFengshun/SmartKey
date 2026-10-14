package dev.lovekey.open.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.net.Uri
import java.util.concurrent.Executors

/** Transparent picker trampoline; never opens MainActivity or an assistant page. */
class KeyboardImageActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private var completed = false
    private var imageUri: Uri? = null
    private val requestId: String get() = intent.getStringExtra("request_id").orEmpty()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!KeyboardImageImport.exists(requestId)) { finish(); return }
        imageUri = savedInstanceState?.getString("image_uri")?.let(Uri::parse)
        if (imageUri != null) {
            recognizeImage(imageUri!!)
        } else if (savedInstanceState == null) {
            try {
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    type = "image/*"
                    addCategory(Intent.CATEGORY_OPENABLE)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, 41)
            } catch (_: Exception) {
                deliver(null, "无法打开图片选择器，请稍后再试")
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 41) return
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) {
            deliver(null, "已取消选图")
            return
        }
        if (!KeyboardImageImport.exists(requestId)) { finish(); return }
        imageUri = uri
        recognizeImage(uri)
    }

    private fun recognizeImage(uri: Uri) {
        worker.submit {
            OcrImporter.recognize(applicationContext, uri) { text, error ->
                runOnUiThread {
                    if (!isDestroyed) deliver(text, error ?: if (text.isNullOrBlank()) "没有识别到文字，请选择更清晰的截图" else null)
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        imageUri?.let { outState.putString("image_uri", it.toString()) }
        super.onSaveInstanceState(outState)
    }

    private fun deliver(text: String?, error: String?) {
        if (completed) return
        completed = true
        KeyboardImageImport.complete(requestId, text, error)
        finish()
        overridePendingTransition(0, 0)
    }

    override fun onDestroy() {
        if (isFinishing && !completed) KeyboardImageImport.complete(requestId, null, "已取消选图")
        worker.shutdownNow()
        super.onDestroy()
    }
}
