package dev.lovekey.open.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions

internal object OcrImporter {
    /** Called off the main thread. Scale large images and keep pixels on this device. */
    fun recognize(context: Context, uri: Uri, result: (String?, String?) -> Unit) {
        val bitmap = try {
            if (Build.VERSION.SDK_INT >= 28) {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                    val ratio = minOf(1f, 2400f / maxOf(info.size.width, info.size.height))
                    decoder.setTargetSize(maxOf(1, (info.size.width * ratio).toInt()), maxOf(1, (info.size.height * ratio).toInt()))
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            } else {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2400) sample *= 2
                val decoded = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
                    ?: throw IllegalArgumentException()
                val orientation = context.contentResolver.openInputStream(uri)?.use {
                    ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                } ?: ExifInterface.ORIENTATION_NORMAL
                val matrix = Matrix().apply {
                    when (orientation) {
                        ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
                        ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
                        ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(270f)
                        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> postScale(-1f, 1f)
                        ExifInterface.ORIENTATION_FLIP_VERTICAL -> postScale(1f, -1f)
                        ExifInterface.ORIENTATION_TRANSPOSE -> { postScale(-1f, 1f); postRotate(90f) }
                        ExifInterface.ORIENTATION_TRANSVERSE -> { postScale(-1f, 1f); postRotate(270f) }
                    }
                }
                if (matrix.isIdentity) decoded else android.graphics.Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true).also { decoded.recycle() }
            }
        } catch (_: Exception) { result(null, "无法打开这张图片，请换一张清晰截图。"); return }
        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { text -> result(text.text, null) }
            .addOnFailureListener { result(null, "图片识别失败，请换一张清晰截图。") }
            .addOnCompleteListener { recognizer.close(); bitmap.recycle() }
    }
}
