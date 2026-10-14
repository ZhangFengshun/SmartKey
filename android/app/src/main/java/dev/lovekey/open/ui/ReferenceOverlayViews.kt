package dev.lovekey.open.ui

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.*
import dev.lovekey.open.ai.ReferenceKeyboardCatalog
import dev.lovekey.open.ai.ReferenceKeyboardPersona

/** Keyboard-only panels; callers own navigation, state and panel height. */
object ReferenceOverlayViews {
    data class Palette(val background: Int, val key: Int, val function: Int, val text: Int, val muted: Int, val accent: Int)
    private fun dp(c: Context, n: Int) = (c.resources.displayMetrics.density * n).toInt()
    private fun shape(c: Context, color: Int, radius: Int = 9) = GradientDrawable().apply { setColor(color); cornerRadius = dp(c, radius).toFloat() }
    private fun column(c: Context, p: Palette) = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL; background = shape(c, p.background, 0)
        setPadding(dp(c, 7), dp(c, 5), dp(c, 7), dp(c, 5))
    }
    private fun row(c: Context) = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun label(c: Context, p: Palette, value: String, size: Float = 13f, color: Int = p.text) = TextView(c).apply {
        text = value; textSize = size; setTextColor(color); gravity = Gravity.CENTER
        contentDescription = value; includeFontPadding = false
    }
    private fun button(c: Context, p: Palette, value: String, onClick: () -> Unit) = label(c, p, value).apply {
        background = shape(c, p.key); isFocusable = true; isClickable = true
        setPadding(dp(c, 3), dp(c, 5), dp(c, 3), dp(c, 5)); setOnClickListener { onClick() }
    }
    private fun header(c: Context, p: Palette, title: String, onBack: () -> Unit): LinearLayout = row(c).apply {
        addView(button(c, p, "‹") { onBack() }.apply { contentDescription = "返回" }, LinearLayout.LayoutParams(dp(c, 36), dp(c, 32)))
        addView(label(c, p, title, 16f).apply { typeface = Typeface.DEFAULT_BOLD }, LinearLayout.LayoutParams(0, dp(c, 36), 1f))
        addView(View(c), LinearLayout.LayoutParams(dp(c, 36), 1))
    }
    private fun cell(c: Context): LinearLayout.LayoutParams = LinearLayout.LayoutParams(0, dp(c, 42), 1f).apply {
        setMargins(dp(c, 3), dp(c, 3), dp(c, 3), dp(c, 3))
    }
    fun more(c: Context, p: Palette, onAction: (String) -> Unit, onBack: () -> Unit): View = column(c, p).apply {
        addView(header(c, p, "更多", onBack))
        val items = listOf("切换输入法" to "switch_input_method", "人设市场" to "persona_market", "我的人设" to "my_personas", "键盘皮肤" to "keyboard_skins", "定制聊天人设" to "custom_persona")
        items.chunked(3).forEach { group -> addView(row(c).apply {
            group.forEach { (title, id) -> val v = button(c, p, title) { onAction(id) }; addView(v, cell(c).apply { height = dp(c, 74) }) }
        }) }
    }
    fun relationships(c: Context, p: Palette, selected: String, onSelect: (String) -> Unit, onBack: () -> Unit, onAutoRelationship: () -> Unit = { onSelect("通用") }): View = column(c, p).apply {
        addView(row(c).apply {
            addView(label(c, p, "对方是你的", 15f), LinearLayout.LayoutParams(0, dp(c, 34), 1f))
            addView(button(c, p, "智能判断关系", onAutoRelationship), LinearLayout.LayoutParams(dp(c, 112), dp(c, 30)))
            addView(button(c, p, "⌄", onBack).apply { contentDescription = "折叠关系选择" }, LinearLayout.LayoutParams(dp(c, 36), dp(c, 30)))
        })
        ReferenceKeyboardCatalog.relationshipOptions.chunked(3).forEach { group -> addView(row(c).apply {
            group.forEach { title -> val v = button(c, p, title) { onSelect(title) }.apply {
                isSelected = selected == title
                if (isSelected) { setTextColor(p.accent); background = shape(c, p.function) }
            }; addView(v, cell(c)) }
        }) }
    }
    fun opener(c: Context, p: Palette, onTopic: (ReferenceKeyboardPersona) -> Unit, onClear: () -> Unit, onSend: () -> Unit, onBack: () -> Unit): View = column(c, p).apply {
        addView(header(c, p, "开场白", onBack))
        val grid = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        ReferenceKeyboardCatalog.openerTopics.chunked(3).forEach { group -> grid.addView(row(c).apply {
            group.forEach { topic -> val v = button(c, p, "${topic.emoji} ${topic.name}") { onTopic(topic) }.apply { contentDescription = topic.name }; addView(v, cell(c)) }
            repeat(3 - group.size) { addView(View(c), LinearLayout.LayoutParams(0, dp(c, 42), 1f)) }
        }) }
        addView(ScrollView(c).apply { addView(grid); isFillViewport = false }, LinearLayout.LayoutParams(-1, 0, 1f))
        addView(row(c).apply {
            addView(button(c, p, "清空", onClear), LinearLayout.LayoutParams(dp(c, 50), dp(c, 34)))
            addView(label(c, p, "·任意点击，轻松Sayhi·", 12f, p.muted), LinearLayout.LayoutParams(0, dp(c, 34), 1f))
            addView(button(c, p, "发送", onSend), LinearLayout.LayoutParams(dp(c, 55), dp(c, 34)))
        })
    }
    fun rewriteHeader(c: Context, p: Palette, text: String, relationship: String, onEdit: () -> Unit, onOptimize: () -> Unit, onRelationship: () -> Unit, onClose: () -> Unit): View = column(c, p).apply {
        background = shape(c, p.function, 12)
        addView(row(c).apply {
            addView(label(c, p, "超会说", 17f).apply { typeface = Typeface.DEFAULT_BOLD }, LinearLayout.LayoutParams(0, dp(c, 32), 1f))
            addView(button(c, p, "×", onClose).apply { contentDescription = "关闭超会说" }, LinearLayout.LayoutParams(dp(c, 32), dp(c, 32)))
        })
        addView(button(c, p, text.ifBlank { "输入想优化的话" }, onEdit).apply { gravity = Gravity.TOP or Gravity.LEFT; maxLines = 3; contentDescription = "编辑待优化文字" }, LinearLayout.LayoutParams(-1, 0, 1f))
        addView(row(c).apply {
            addView(button(c, p, "$relationship ⌄", onRelationship).apply { contentDescription = "关系：$relationship" }, LinearLayout.LayoutParams(dp(c, 105), dp(c, 32)))
            addView(View(c), LinearLayout.LayoutParams(0, 1, 1f))
            addView(button(c, p, "优化", onOptimize).apply { setTextColor(p.accent) }, LinearLayout.LayoutParams(dp(c, 68), dp(c, 32)))
        })
    }
    fun intimacy(c: Context, p: Palette, initial: Int, onConfirm: (Int) -> Unit, onBack: () -> Unit): View = column(c, p).apply {
        addView(header(c, p, "亲密度", onBack))
        val value = label(c, p, "${initial.coerceIn(0, 100)}%", 24f, p.accent)
        addView(value, LinearLayout.LayoutParams(-1, dp(c, 48)))
        val slider = SeekBar(c).apply {
            max = 100; progress = initial.coerceIn(0, 100); contentDescription = "调整亲密度"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(v: SeekBar?, n: Int, user: Boolean) { value.text = "$n%" }
                override fun onStartTrackingTouch(v: SeekBar?) {}
                override fun onStopTrackingTouch(v: SeekBar?) {}
            })
        }
        addView(slider, LinearLayout.LayoutParams(-1, dp(c, 48)))
        addView(button(c, p, "确认") { onConfirm(slider.progress) }, LinearLayout.LayoutParams(-1, dp(c, 42)))
    }
    fun skins(c: Context, p: Palette, selected: String, onSelect: (String) -> Unit, onBack: () -> Unit): View = column(c, p).apply {
        addView(header(c, p, "键盘皮肤", onBack))
        listOf("lavender" to "淡紫", "light" to "浅色", "dark" to "深色").forEach { (id, title) ->
            addView(button(c, p, title) { onSelect(id) }.apply { isSelected = id == selected; if (isSelected) setTextColor(p.accent) }, LinearLayout.LayoutParams(-1, dp(c, 48)).apply { topMargin = dp(c, 4) })
        }
    }
}
