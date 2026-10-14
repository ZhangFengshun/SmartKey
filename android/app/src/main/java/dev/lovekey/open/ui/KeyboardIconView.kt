package dev.lovekey.open.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.view.View

/** Small native icons, drawn as vectors so keyboard controls stay sharp. */
internal class KeyboardIconView(context: Context, private val kind: String, private val color: Int,
                                private val percent: Int = 0) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val side = minOf(width, height).toFloat()
        canvas.save()
        canvas.translate((width - side) / 2, (height - side) / 2)
        canvas.scale(side / 100, side / 100)
        paint.color = color; paint.shader = null; paint.strokeWidth = 4f
        paint.strokeCap = Paint.Cap.ROUND; paint.strokeJoin = Paint.Join.ROUND
        paint.style = Paint.Style.STROKE
        when (kind) {
            "keyboard" -> {
                canvas.drawRoundRect(21f, 31f, 79f, 71f, 7f, 7f, paint)
                for (y in listOf(42f, 51f)) for (x in listOf(32f, 44f, 56f, 68f)) canvas.drawPoint(x, y, paint)
                canvas.drawLine(36f, 61f, 64f, 61f, paint)
            }
            "tools" -> for (x in listOf(25f, 53f)) for (y in listOf(25f, 53f))
                canvas.drawRoundRect(x, y, x + 20, y + 20, 5f, 5f, paint)
            "hide" -> { canvas.drawLine(31f, 43f, 50f, 61f, paint); canvas.drawLine(50f, 61f, 69f, 43f, paint) }
            "ocr" -> {
                canvas.drawPath(Path().apply { moveTo(27f, 22f); lineTo(27f, 61f); quadTo(27f, 70f, 36f, 70f); lineTo(75f, 70f) }, paint)
                canvas.drawPath(Path().apply { moveTo(22f, 29f); lineTo(62f, 29f); quadTo(71f, 29f, 71f, 38f); lineTo(71f, 78f) }, paint)
            }
            "delete" -> {
                canvas.drawPath(Path().apply { moveTo(36f, 29f); lineTo(79f, 29f); lineTo(79f, 71f); lineTo(36f, 71f); lineTo(19f, 50f); close() }, paint)
                canvas.drawLine(45f, 41f, 62f, 59f, paint); canvas.drawLine(62f, 41f, 45f, 59f, paint)
            }
            "hi" -> {
                canvas.drawPath(Path().apply { moveTo(67f, 27f); cubicTo(27f, 14f, 14f, 61f, 35f, 69f); lineTo(27f, 79f); lineTo(47f, 73f); cubicTo(68f, 77f, 83f, 65f, 78f, 44f) }, paint)
                paint.style = Paint.Style.FILL; paint.textSize = 29f; paint.textAlign = Paint.Align.CENTER
                canvas.drawText("Hi", 56f, 51f, paint)
            }
            "heart" -> {
                paint.style = Paint.Style.FILL
                paint.shader = LinearGradient(0f, 18f, 0f, 84f, Color.rgb(255, 197, 210), Color.rgb(250, 119, 155), Shader.TileMode.CLAMP)
                canvas.drawPath(Path().apply { moveTo(50f, 84f); cubicTo(11f, 58f, 6f, 31f, 26f, 22f); cubicTo(38f, 17f, 47f, 24f, 50f, 31f); cubicTo(56f, 19f, 69f, 17f, 79f, 25f); cubicTo(99f, 44f, 76f, 67f, 50f, 84f); close() }, paint)
                paint.shader = null; paint.color = Color.WHITE; paint.textSize = 26f; paint.textAlign = Paint.Align.CENTER
                paint.isFakeBoldText = true; canvas.drawText("$percent%", 50f, 57f, paint); paint.isFakeBoldText = false
            }
        }
        canvas.restore()
    }
}
