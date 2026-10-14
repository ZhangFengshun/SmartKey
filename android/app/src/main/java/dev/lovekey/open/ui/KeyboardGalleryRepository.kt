package dev.lovekey.open.ui

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size

data class KeyboardGalleryImage(val id: Long, val uri: Uri, val timeMillis: Long)
data class KeyboardGalleryResult(val images: List<KeyboardGalleryImage>, val error: String? = null)
data class KeyboardThumbnailResult(val bitmap: Bitmap?, val error: String? = null)

/** No eager reads: call query/loadThumbnail on a worker only after explicit gallery entry. */
class KeyboardGalleryRepository(context: Context) {
    private val context = context.applicationContext
    fun query(limit: Int = 300): KeyboardGalleryResult {
        if (!hasPermission(context)) return KeyboardGalleryResult(emptyList(), "请允许相册权限后查看截图")
        return try {
            val images = ArrayList<KeyboardGalleryImage>()
            val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_TAKEN, MediaStore.Images.Media.DATE_ADDED)
            context.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection,
                null, null, "${MediaStore.Images.Media.DATE_ADDED} DESC, ${MediaStore.Images.Media._ID} DESC")?.use { cursor ->
                while (images.size < limit.coerceIn(1, 1000) && cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    val taken = cursor.getLong(1)
                    val added = cursor.getLong(2) * 1000L
                    // Gallery order follows when the image entered this device (including screenshots).
                    images.add(KeyboardGalleryImage(id, ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id), if (added > 0) added else taken))
                }
            } ?: return KeyboardGalleryResult(emptyList(), "暂时无法读取相册，请稍后重试")
            KeyboardGalleryResult(images.sortedWith(compareByDescending<KeyboardGalleryImage> { it.timeMillis }.thenByDescending { it.id }))
        } catch (_: SecurityException) { KeyboardGalleryResult(emptyList(), "相册权限已失效，请重新授权") }
        catch (_: Exception) { KeyboardGalleryResult(emptyList(), "无法读取相册，请稍后重试") }
    }
    fun loadThumbnail(image: KeyboardGalleryImage, sizePx: Int = 240): KeyboardThumbnailResult {
        val size = sizePx.coerceIn(48, 1024)
        return try {
            val bitmap = if (Build.VERSION.SDK_INT >= 29) {
                context.contentResolver.loadThumbnail(image.uri, Size(size, size), null)
            } else {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(image.uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= size && bounds.outHeight / (sample * 2) >= size) sample *= 2
                context.contentResolver.openInputStream(image.uri)?.use {
                    BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
                }
            }
            KeyboardThumbnailResult(bitmap, if (bitmap == null) "这张图片已不可用" else null)
        } catch (_: SecurityException) { KeyboardThumbnailResult(null, "无法访问这张图片，请重新授权") }
        catch (_: Exception) { KeyboardThumbnailResult(null, "图片已删除或暂时无法读取") }
    }
    companion object {
        fun permissionName(): String = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
        fun hasPermission(context: Context): Boolean = context.checkSelfPermission(permissionName()) == PackageManager.PERMISSION_GRANTED
    }
}

/** Insertion order is the user's selection order; deselect/reselect moves an image to the end. */
class KeyboardGallerySelection(private val maximum: Int = 3) {
    private val selected = LinkedHashMap<Long, KeyboardGalleryImage>()
    fun toggle(image: KeyboardGalleryImage): Boolean {
        if (selected.remove(image.id) != null) return false
        if (selected.size >= maximum.coerceAtLeast(1)) return false
        selected[image.id] = image
        return true
    }
    fun position(id: Long): Int = selected.keys.indexOf(id).let { if (it < 0) 0 else it + 1 }
    fun images(): List<KeyboardGalleryImage> = selected.values.toList()
    fun clear() = selected.clear()
}
