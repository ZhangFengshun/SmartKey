package dev.lovekey.open.ui

import android.content.Context
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.*

internal object Ui {
    val ink = Color.rgb(33, 35, 51)
    val muted = Color.rgb(124, 128, 148)
    val accent = Color.rgb(97, 112, 248)
    val background = Color.rgb(246, 247, 252)
    fun dp(context: Context, value: Int) = (context.resources.displayMetrics.density * value).toInt()
    fun round(context: Context, color: Int, radius: Int = 20, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(context, radius).toFloat()
        if (stroke != null) setStroke(dp(context, 1), stroke)
    }
    fun text(context: Context, value: String, size: Float = 16f, color: Int = ink, bold: Boolean = false) = TextView(context).apply {
        text = value; textSize = size; setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif", Typeface.BOLD)
        setLineSpacing(dp(context, 3).toFloat(), 1f)
    }
    fun column(context: Context) = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    fun row(context: Context) = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    fun button(context: Context, label: String, primary: Boolean = false, action: () -> Unit) = text(context, label, 15f, if (primary) Color.WHITE else accent, true).apply {
        gravity = Gravity.CENTER; minHeight = dp(context, 48)
        setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10))
        background = round(context, if (primary) accent else Color.rgb(237, 239, 255), 16)
        isClickable = true; isFocusable = true
        setOnClickListener { action() }
    }
}

/** Drawn locally; no copied commercial artwork or remote image dependency. */
internal class HeartView(context: Context, private val percent: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(canvas: Canvas) {
        val scale = minOf(width / 220f, height / 184f)
        canvas.save(); canvas.translate((width - 220 * scale) / 2, (height - 184 * scale) / 2); canvas.scale(scale, scale)
        val heart = Path().apply {
            moveTo(110f, 34f); cubicTo(63f, -12f, 10f, 12f, 10f, 61f)
            cubicTo(10f, 115f, 60f, 151f, 110f, 180f)
            cubicTo(160f, 151f, 210f, 115f, 210f, 61f)
            cubicTo(210f, 12f, 157f, -12f, 110f, 34f); close()
        }
        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(30f, 0f, 180f, 184f, intArrayOf(0xFFFDFDFF.toInt(), 0xFFDFE4FF.toInt(), 0xFFFDE7F3.toInt()), null, Shader.TileMode.CLAMP)
        canvas.drawPath(heart, paint)
        canvas.save(); canvas.clipPath(heart)
        val level = 180f - percent * 1.65f
        val water = Path().apply { moveTo(0f, level); cubicTo(65f, level - 18, 120f, level + 18, 220f, level); lineTo(220f, 184f); lineTo(0f, 184f); close() }
        paint.shader = LinearGradient(0f, level, 0f, 184f, 0xFFB9B5FF.toInt(), 0xFFEC82B0.toInt(), Shader.TileMode.CLAMP)
        canvas.drawPath(water, paint); canvas.restore()
        paint.shader = null; paint.style = Paint.Style.STROKE; paint.strokeWidth = 2.4f; paint.color = Color.WHITE
        canvas.drawPath(heart, paint)
        paint.strokeWidth = 5f; paint.strokeCap = Paint.Cap.ROUND; paint.color = 0xAAFFFFFF.toInt()
        canvas.drawPath(Path().apply { moveTo(32f, 74f); cubicTo(23f, 34f, 53f, 21f, 76f, 32f) }, paint)
        canvas.restore()
    }
}
