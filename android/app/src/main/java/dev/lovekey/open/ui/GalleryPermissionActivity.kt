package dev.lovekey.open.ui

import android.app.Activity
import android.os.Bundle

/** Transparent permission trampoline; returns to the existing editor, never MainActivity. */
class GalleryPermissionActivity : Activity() {
    private val requestId: String get() = intent.getStringExtra("request_id").orEmpty()
    private var completed = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!GalleryPermissionBridge.exists(requestId)) { finish(); return }
        if (KeyboardGalleryRepository.hasPermission(this)) { deliver(true, null); return }
        if (savedInstanceState == null) {
            try { requestPermissions(arrayOf(KeyboardGalleryRepository.permissionName()), 52) }
            catch (_: Exception) { deliver(false, "无法请求相册权限，请稍后重试") }
        }
    }
    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (code == 52) {
            val granted = KeyboardGalleryRepository.hasPermission(this)
            deliver(granted, if (granted) null else "未获得相册权限，可重新授权")
        }
    }
    private fun deliver(granted: Boolean, error: String?) {
        if (completed) return
        completed = true
        GalleryPermissionBridge.complete(requestId, granted, error)
        finish()
        overridePendingTransition(0, 0)
    }
    override fun onDestroy() {
        if (isFinishing && !completed) GalleryPermissionBridge.complete(requestId, false, "已取消相册授权")
        super.onDestroy()
    }
}
