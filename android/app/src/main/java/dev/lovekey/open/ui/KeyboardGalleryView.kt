package dev.lovekey.open.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.*
import java.util.concurrent.Executors

/** Open only for an explicit gallery action. The owner supplies the transparent permission launch. */
class KeyboardGalleryView(
    context: Context,
    private val palette: ReferenceOverlayViews.Palette,
    private val onClose: () -> Unit,
    private val onAnalyze: (List<KeyboardGalleryImage>) -> Unit,
    private val onPermission: () -> Unit,
) : LinearLayout(context) {
    private val repository = KeyboardGalleryRepository(context)
    private val selection = KeyboardGallerySelection(3)
    private val worker = Executors.newSingleThreadExecutor()
    private val bitmaps = ArrayList<Bitmap>()
    private var generation = 0
    private var disposed = false
    private val grid = LinearLayout(context).apply { orientation = VERTICAL }
    private val status = TextView(context)
    private val selectionCount = text("最多选择3张", 11f).apply { setTextColor(palette.muted) }
    private val badges = LinkedHashMap<Long, TextView>()
    private val analyze = text("立即分析", 14f).apply {
        background = shape(palette.accent); setTextColor(android.graphics.Color.WHITE)
        setOnClickListener { if (selection.images().isNotEmpty()) onAnalyze(selection.images()) }
    }

    init {
        orientation = VERTICAL; setBackgroundColor(palette.background)
        setPadding(dp(7), dp(4), dp(7), dp(5))
        addView(LinearLayout(context).apply {
            orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(text("选择聊天截图（最多3张）", 15f), LayoutParams(0, dp(34), 1f))
            addView(text("×", 22f).apply { contentDescription = "关闭聊天截图选择"; setOnClickListener { onClose() } }, LayoutParams(dp(36), dp(34)))
        })
        addView(text("按时间从旧到新排序，生成更准", 11f).apply { setTextColor(palette.muted) }, LayoutParams(-1, dp(24)))
        status.apply { gravity = Gravity.CENTER; textSize = 12f; setTextColor(palette.muted) }
        grid.addView(status, LayoutParams(-1, dp(42)))
        addView(ScrollView(context).apply { addView(grid) }, LayoutParams(-1, 0, 1f))
        addView(LinearLayout(context).apply {
            orientation = HORIZONTAL; gravity = Gravity.END or Gravity.CENTER_VERTICAL
            addView(selectionCount, LayoutParams(0, dp(36), 1f))
            addView(analyze, LayoutParams(dp(104), dp(36)))
        })
        refreshBadges()
    }

    /** Call after adding the view, and again after permission completion. Never reads at construction. */
    fun refresh() {
        if (disposed) return
        generation++
        val revision = generation
        selection.clear(); badges.clear(); grid.removeAllViews(); refreshBadges()
        releaseBitmaps()
        if (!KeyboardGalleryRepository.hasPermission(context)) {
            showStatus("允许相册权限后，即可在键盘中选择截图", "允许访问相册") { onPermission() }
            return
        }
        showStatus("正在读取相册…")
        worker.submit {
            // Keep keyboard memory bounded even when the device has thousands of photos.
            val result = repository.query(90)
            post {
                if (disposed || revision != generation) return@post
                if (result.error != null) { showStatus(result.error, "重试") { refresh() }; return@post }
                if (result.images.isEmpty()) { showStatus("相册中没有图片，请先保存聊天截图", "刷新") { refresh() }; return@post }
                showImages(result.images, revision)
            }
        }
    }

    private fun showImages(images: List<KeyboardGalleryImage>, revision: Int) {
        grid.removeAllViews()
        images.chunked(3).forEach { group ->
            grid.addView(LinearLayout(context).apply {
                orientation = HORIZONTAL
                group.forEach { image ->
                    val frame = FrameLayout(context).apply {
                        background = shape(palette.key); clipToOutline = true
                        contentDescription = "聊天截图 ${image.id}，未选择"
                        isFocusable = true
                        setOnClickListener {
                            val wasSelected = selection.position(image.id) > 0
                            if (!wasSelected && selection.images().size >= 3) {
                                Toast.makeText(context, "最多选择3张截图", Toast.LENGTH_SHORT).show()
                            } else { selection.toggle(image); refreshBadges() }
                        }
                    }
                    val photo = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
                    frame.addView(photo, FrameLayout.LayoutParams(-1, -1))
                    val badge = text("", 12f).apply { background = shape(palette.function); setTextColor(palette.accent); importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
                    badges[image.id] = badge
                    frame.addView(badge, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(4), dp(4)) })
                    addView(frame, LayoutParams(0, dp(90), 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
                    worker.submit {
                        val result = repository.loadThumbnail(image, dp(90))
                        post {
                            if (disposed || revision != generation) { result.bitmap?.recycle(); return@post }
                            result.bitmap?.let { bitmaps.add(it); photo.setImageBitmap(it) }
                            if (result.bitmap == null) photo.contentDescription = result.error
                        }
                    }
                }
                repeat(3 - group.size) { addView(View(context), LayoutParams(0, dp(90), 1f)) }
            })
        }
        refreshBadges()
    }

    private fun refreshBadges() {
        badges.forEach { (id, badge) ->
            val position = selection.position(id)
            badge.text = if (position == 0) "○" else position.toString()
            (badge.parent as? View)?.apply {
                isSelected = position > 0
                contentDescription = if (position == 0) "聊天截图 $id，未选择" else "聊天截图 $id，已选择第${position}张"
            }
        }
        analyze.isEnabled = selection.images().isNotEmpty()
        analyze.alpha = if (analyze.isEnabled) 1f else .4f
        val count = selection.images().size
        selectionCount.text = if (count == 0) "最多选择3张" else "已选${count}张 · 按编号顺序分析"
        selectionCount.contentDescription = selectionCount.text
    }
    private fun showStatus(message: String, action: String? = null, click: (() -> Unit)? = null) {
        grid.removeAllViews()
        status.text = message; grid.addView(status, LayoutParams(-1, dp(48)))
        if (action != null) grid.addView(text(action, 13f).apply {
            background = shape(palette.key); setTextColor(palette.accent); setOnClickListener { click?.invoke() }
        }, LayoutParams(-1, dp(40)))
    }
    private fun text(value: String, size: Float) = TextView(context).apply {
        text = value; contentDescription = value; textSize = size; setTextColor(palette.text)
        gravity = Gravity.CENTER; isFocusable = true
    }
    private fun dp(n: Int) = (context.resources.displayMetrics.density * n).toInt()
    private fun shape(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(9).toFloat() }
    private fun releaseBitmaps() { bitmaps.forEach { if (!it.isRecycled) it.recycle() }; bitmaps.clear() }
    fun dispose() {
        if (disposed) return
        disposed = true; generation++; worker.shutdownNow()
        grid.removeAllViews(); releaseBitmaps()
    }
}
