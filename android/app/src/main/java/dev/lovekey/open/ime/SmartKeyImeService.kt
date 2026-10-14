package dev.lovekey.open.ime

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputMethodManager
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.lovekey.open.ai.AiClient
import dev.lovekey.open.ai.AiException
import dev.lovekey.open.ai.AiSettings
import dev.lovekey.open.ai.ReferenceKeyboardCatalog
import dev.lovekey.open.ai.ReferenceSymbolsCatalog
import dev.lovekey.open.ui.ReferenceOverlayViews
import dev.lovekey.open.ui.KeyboardGalleryView
import dev.lovekey.open.ui.KeyboardGalleryImage
import dev.lovekey.open.ui.GalleryPermissionBridge
import dev.lovekey.open.ui.GalleryPermissionActivity
import dev.lovekey.open.ui.OcrImporter
import dev.lovekey.open.ai.Personas
import dev.lovekey.open.ai.ReplyCandidate
import dev.lovekey.open.ai.ReplyRequest
import dev.lovekey.open.ai.ReplyTask
import dev.lovekey.open.data.ContactMemory
import dev.lovekey.open.data.SettingsRepository
import dev.lovekey.open.pinyin.AospPinyinEngine
import dev.lovekey.open.pinyin.PinyinEngine
import dev.lovekey.open.ui.KeyboardIconView
import dev.lovekey.open.ui.KeyboardPreferences
import dev.lovekey.open.ui.KeyboardImageActivity
import dev.lovekey.open.ui.KeyboardImageImport
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Generation fills the current draft; only the user's Send tap requests a host send action. */
open class SmartKeyImeService : InputMethodService() {
    private enum class Panel { KEYBOARD, AI }
    private enum class Target { HOST, SOURCE }
    private data class Palette(val background: Int, val key: Int, val function: Int, val text: Int, val muted: Int, val accent: Int)

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newFixedThreadPool(2)
    private val sessions = EditorSessionGuard()
    private var pending: Future<*>? = null
    private lateinit var engine: PinyinEngine
    private lateinit var repository: SettingsRepository
    private lateinit var root: LinearLayout
    private lateinit var body: LinearLayout
    private lateinit var colors: Palette
    private var candidateRow: LinearLayout? = null
    private var composingLabel: TextView? = null
    private var sourcePreview: TextView? = null
    private var statusLabel: TextView? = null
    private var resultsColumn: LinearLayout? = null
    private var panel = Panel.AI
    private var target = Target.HOST
    private var task = ReplyTask.REPLY
    private var targetLanguage = "英语"
    private var sensitive = false
    private var numeric = false
    private var chinese = true
    private var preferredChinese = true
    private var editorIdentity: Triple<String?, Int, Int>? = null
    private val compositionSelection = CompositionSelectionGuard()
    private var selectionCheck: Runnable? = null
    private var symbols = false
    private var fullSymbols = false
    private var symbolCategory = 0
    private var relationship = "通用"
    private var replySource = ""
    private var openerContext = ""
    private var gallery: KeyboardGalleryView? = null
    private var permissionEditor: Triple<String?, Int, Int>? = null
    private var permissionId: String? = null
    private var permissionReady = false
    private var permissionExpires = 0L
    private var shifted = false
    private var loading = false
    private var source = ""
    private var lastImportedClipboard: String? = null
    private var status = "复制对方消息后打开键盘，自动导入到帮你回"
    private var settings = AiSettings()
    private var selectedMemory: ContactMemory? = null
    private var candidates: List<ReplyCandidate> = emptyList()
    private var candidateToken: EditorSessionGuard.Token? = null
    private var selectedCandidate = 0
    private var replyScroll: HorizontalScrollView? = null
    private var insertedReply: String? = null
    private var insertedReplyToken: EditorSessionGuard.Token? = null
    private var insertedDraftSnapshot: ReplyDraftSnapshot? = null
    private data class ImageImport(
        val editor: Triple<String?, Int, Int>,
        val memory: ContactMemory?,
        val pickerPackage: String?,
        val expiresAt: Long = SystemClock.elapsedRealtime() + 300_000,
        var id: String? = null,
        var ready: Boolean = false,
        var applied: Boolean = false,
        var text: String? = null,
        var error: String? = null,
    )
    private var imageImport: ImageImport? = null
    private var deleteHeld = false
    private val deleteRepeat = object : Runnable {
        override fun run() {
            if (deleteHeld) { delete(); main.postDelayed(this, 70) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        repository = SettingsRepository(this)
        engine = AospPinyinEngine(this)
    }

    override fun onCreateInputView(): View {
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        render()
        return root
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        val nextIdentity = attribute?.let { Triple(it.packageName, it.fieldId, it.inputType) }
        val nextSensitive = InputSafety.isSensitive(attribute?.inputType ?: 0)
        if (permissionId != null && (nextSensitive || SystemClock.elapsedRealtime() > permissionExpires ||
            (nextIdentity != null && nextIdentity != permissionEditor))) clearGalleryPermission()
        imageImport?.let { ticket ->
            if (nextSensitive || SystemClock.elapsedRealtime() > ticket.expiresAt ||
                (nextIdentity != null && nextIdentity.first != packageName &&
                    nextIdentity.first != ticket.pickerPackage && nextIdentity != ticket.editor)) {
                clearImageImport()
            }
        }
        val preserveComposition = restarting && nextIdentity != null && nextIdentity == editorIdentity &&
            !sensitive && !nextSensitive && target == Target.HOST
        editorIdentity = nextIdentity
        cancelPending()
        sessions.beginSession()
        insertedReply = null
        insertedReplyToken = null
        insertedDraftSnapshot = null
        if (!preserveComposition) { engine.reset(); clearCompositionTracking() }
        sensitive = nextSensitive
        numeric = (attribute?.inputType ?: 0) and InputType.TYPE_MASK_CLASS in setOf(
            InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_PHONE, InputType.TYPE_CLASS_DATETIME,
        )
        chinese = if (sensitive) false else preferredChinese
        target = Target.HOST
        panel = if (sensitive || numeric || preserveComposition) Panel.KEYBOARD else Panel.AI
        task = ReplyTask.REPLY
        symbols = numeric
        shifted = false
        source = ""
        replySource = ""; openerContext = ""; relationship = "通用"; fullSymbols = false
        lastImportedClipboard = null
        selectedMemory = null
        candidates = emptyList()
        candidateToken = null
        selectedCandidate = 0
        status = "复制对方消息后打开键盘，自动导入到帮你回"
        loadSettings()
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        loadSettings()
        if (permissionEditor == editorIdentity && permissionId != null && permissionReady) {
            showInlineGallery(); return
        }
        if (consumeImageImport()) {
            if (::root.isInitialized) render()
            return
        }
        if (!restarting && !sensitive && !numeric && engine.raw.isEmpty()) {
            panel = Panel.AI
            task = ReplyTask.REPLY
            target = Target.HOST
            source = ""
            lastImportedClipboard = null
            invalidateResults()
        }
        if (panel == Panel.AI && !sensitive) importClipboard(automatic = true)
        if (::root.isInitialized) render()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        gallery?.dispose(); gallery = null
        if (imageImport?.applied == true) clearImageImport()
        cancelPending()
        candidates = emptyList()
        candidateToken = null
        status = "复制对方消息后打开键盘，自动导入到帮你回"
        stopDeleteRepeat()
        super.onFinishInputView(finishingInput)
    }

    override fun onFinishInput() {
        cancelPending()
        sessions.endSession()
        insertedReply = null
        insertedReplyToken = null
        insertedDraftSnapshot = null
        engine.reset()
        clearCompositionTracking()
        editorIdentity = null
        source = ""
        lastImportedClipboard = null
        candidates = emptyList()
        candidateToken = null
        target = Target.HOST
        super.onFinishInput()
    }

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        if (target != Target.HOST || sensitive || engine.raw.isEmpty()) return
        if (newSelStart == newSelEnd && newSelEnd == candidatesEnd) return
        if (oldSelStart == newSelStart && oldSelEnd == newSelEnd) return
        // Selection notifications are asynchronous. An older notification (or
        // an editor omitting composing bounds) is not evidence that the user
        // moved the cursor. Verify the editor's current selection after queued
        // IME writes settle, against the latest successful composing write.
        selectionCheck?.let(main::removeCallbacks)
        val revision = compositionSelection.revision
        val check = Runnable {
            selectionCheck = null
            if (revision != compositionSelection.revision || target != Target.HOST || sensitive || engine.raw.isEmpty()) return@Runnable
            val actual = readEditorSelection() ?: return@Runnable
            if (compositionSelection.hasMoved(actual)) {
                engine.reset()
                clearCompositionTracking()
                currentInputConnection?.finishComposingText()
                refreshComposition()
            }
        }
        selectionCheck = check
        main.postDelayed(check, 40)
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onDestroy() {
        gallery?.dispose(); GalleryPermissionBridge.cancel(permissionId)
        clearImageImport()
        cancelPending()
        stopDeleteRepeat()
        worker.shutdownNow()
        main.removeCallbacksAndMessages(null)
        engine.close()
        super.onDestroy()
    }

    private fun loadSettings(): Boolean = try {
        settings = repository.load()
        true
    } catch (error: AiException) {
        settings = AiSettings()
        invalidateResults()
        status = error.message ?: "请重新配置模型"
        false
    }

    private fun render() {
        colors = palette(KeyboardPreferences.theme(this))
        root.removeAllViews()
        gallery?.dispose(); gallery = null
        root.setBackgroundColor(colors.background)
        if (panel == Panel.AI && KeyboardPreferences.theme(this) == "dark") {
            root.background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(45, 55, 64), Color.rgb(52, 47, 72)))
        }
        root.setPadding(dp(5), dp(5), dp(5), dp(5))
        candidateRow = null
        composingLabel = null
        sourcePreview = null
        resultsColumn = null
        statusLabel = null
        if (panel == Panel.AI && !sensitive && (task != ReplyTask.REWRITE || candidates.isNotEmpty())) renderReplyStrip()
        if (panel == Panel.AI && task == ReplyTask.REWRITE && !sensitive) root.addView(ReferenceOverlayViews.rewriteHeader(this, overlayPalette(), source, relationship, { editSource() }, { generate() }, { showRelationships() }, { showAi(ReplyTask.REPLY) }), LinearLayout.LayoutParams(-1, dp(145)))
        root.addView(toolbar())
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        if (panel == Panel.AI && !sensitive) {
            when (task) {
                ReplyTask.OPENER -> body.addView(ReferenceOverlayViews.opener(this, overlayPalette(), { topic -> source = topic.name; openerContext = topic.prompt; generate() }, { clearHostDraft() }, { sendReply() }, { showAi(ReplyTask.REPLY) }), LinearLayout.LayoutParams(-1, dp(258)))
                ReplyTask.REWRITE -> renderKeyboard()
                else -> renderAi()
            }
        } else renderKeyboard()
        renderCandidates()
    }

    private fun toolbar(): LinearLayout = row().apply {
        setPadding(dp(3), dp(5), dp(3), dp(5))
        if (sensitive) {
            addView(label("安全输入", 14f, colors.muted), LinearLayout.LayoutParams(0, dp(38), 1f))
            addView(icon("hide", "⌄", colors.muted) { requestHideSelf(0) }, LinearLayout.LayoutParams(dp(36), dp(38)))
        } else {
            addView(icon("keyboard", "键盘", colors.accent) {
                finishComposition(); target = Target.HOST; panel = Panel.KEYBOARD; render()
            }, LinearLayout.LayoutParams(0, dp(40), 0.9f).apply { setMargins(dp(2), 0, dp(3), 0) })
            addToolbar(this, "帮你回", panel == Panel.AI && task == ReplyTask.REPLY, 1.75f) { showAi(ReplyTask.REPLY) }
            addToolbar(this, "超会说", panel == Panel.AI && task == ReplyTask.REWRITE, 1.75f) { showAi(ReplyTask.REWRITE) }
            addView(icon("hi", "Hi", colors.text) { showAi(ReplyTask.OPENER) }, LinearLayout.LayoutParams(0, dp(40), 1f))
            addView(icon("tools", "工具", colors.text) { showTools() }, LinearLayout.LayoutParams(0, dp(40), 0.9f))
            addView(icon("heart", "${settings.intimacy}%", Color.WHITE, settings.intimacy) { cycleIntimacy() }, LinearLayout.LayoutParams(0, dp(43), 1f))
            addView(icon("hide", "⌄", colors.muted) { requestHideSelf(0) }, LinearLayout.LayoutParams(0, dp(40), 0.8f))
        }
    }

    private fun icon(kind: String, description: String, color: Int, percent: Int = 0, action: () -> Unit): View =
        KeyboardIconView(this, kind, color, percent).apply {
            contentDescription = description; isClickable = true
            if (kind in setOf("keyboard", "hi", "tools", "hide")) {
                background = GradientDrawable().apply { setColor(this@SmartKeyImeService.colors.function); cornerRadius = dp(24).toFloat() }
            }
            setOnClickListener {
                if (KeyboardPreferences.haptics(this@SmartKeyImeService)) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                action()
            }
        }

    private fun addToolbar(parent: LinearLayout, text: String, selected: Boolean, weight: Float, action: () -> Unit) {
        parent.addView(key(text, 14f, colors.function, if (selected) colors.accent else colors.text, action).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            background = GradientDrawable().apply {
                setColor(this@SmartKeyImeService.colors.function); cornerRadius = dp(22).toFloat()
                if (selected) setStroke(dp(1), this@SmartKeyImeService.colors.accent)
            }
        }, LinearLayout.LayoutParams(0, dp(32), weight).apply { setMargins(dp(3), 0, dp(4), 0) })
    }

    private fun renderKeyboard() {
        if (target == Target.SOURCE) {
            val header = row()
            sourcePreview = label(source.ifBlank { "输入对方消息或任务内容" }, 14f, colors.text).apply {
                maxLines = 2; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), 0, dp(4), 0)
            }
            header.addView(sourcePreview, LinearLayout.LayoutParams(0, dp(50), 1f))
            header.addView(key("完成", 13f, colors.accent, Color.WHITE) {
                finishComposition(); target = Target.HOST; panel = Panel.AI; render()
            }, LinearLayout.LayoutParams(dp(54), dp(42)))
            body.addView(header)
        }
        if (chinese && !symbols && !sensitive) {
            composingLabel = label(if (engine.ready) "中文全拼 · 点候选选词" else "中文引擎未加载 · 可切换英文", 12f, colors.muted)
                .apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), 0, 0, 0) }
            body.addView(composingLabel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(22)))
            val scroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
            candidateRow = row()
            scroll.addView(candidateRow)
            body.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(34)))
        } else {
            body.addView(label(if (sensitive) "密码内容不用于 AI 或拼音学习" else if (symbols) "数字与符号" else "English", 12f, colors.muted),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(26)))
        }
        if (symbols && fullSymbols) { renderFullSymbols(); return }
        if (symbols) {
            addCharacterRow("1234567890", 0)
            addCharacterRow("-/：～（）…@“”", 0)
            val third = row()
            addKey(third, "符号", 1.4f, function = true, size = 13f) { fullSymbols = true; render() }
            "。，、？！.".forEach { character -> addKey(third, if (chinese) character.uppercaseChar().toString() else character.toString(), 1f) { typeText(character.toString()) } }
            addKey(third, "⌫", 1.4f, function = true) { delete() }.also { enableHeldDelete(it) }
            body.addView(third)
        } else {
            addCharacterRow(if (shifted && !chinese) "QWERTYUIOP" else "qwertyuiop", 0)
            addCharacterRow(if (shifted && !chinese) "ASDFGHJKL" else "asdfghjkl", 14)
            val third = row()
            addKey(third, "⇧", 1.35f, function = true, size = 14f) {
                if (chinese) typeText("'") else { shifted = !shifted; render() }
            }
            (if (shifted && !chinese) "ZXCVBNM" else "zxcvbnm").forEach { character ->
                addKey(third, if (chinese) character.uppercaseChar().toString() else character.toString(), 1f) { typeText(character.toString()) }
            }
            addKey(third, "⌫", 1.35f, function = true) { delete() }.also { enableHeldDelete(it) }
            body.addView(third)
        }
        val bottom = row()
        addKey(bottom, if (symbols) "ABC" else "123", 1.05f, function = true, size = 13f) {
            finishComposition(); symbols = !symbols; fullSymbols = false; render()
        }
        addKey(bottom, if (chinese) "，" else ",", 0.8f, size = 18f) { typeText(if (chinese) "，" else ",") }
        addKey(bottom, if (chinese && !sensitive) "空格" else "space", 4.2f, size = 13f) {
            if (engine.raw.isNotEmpty()) choosePinyin(engine.candidates.firstOrNull()?.index ?: -1, fallback = true) else commit(" ")
        }
        addKey(bottom, if (chinese) "中/英" else "EN/中", 1.0f, function = true, size = 12f) {
            if (sensitive) showInputMethods() else {
                finishComposition(); chinese = !chinese; preferredChinese = chinese
                symbols = false; fullSymbols = false; shifted = false; render()
            }
        }.setOnLongClickListener { showInputMethods(); true }
        addKey(bottom, enterLabel(), 1.35f, function = true, accent = true, size = 13f) { enter() }
        body.addView(bottom)
        refreshComposition()
    }

    private fun renderFullSymbols() {
        val category = ReferenceSymbolsCatalog.categories[symbolCategory.coerceIn(ReferenceSymbolsCatalog.categories.indices)]
        category.keys.filter { it != ".." }.chunked(10).forEach { keys ->
            val line = row()
            keys.forEach { value -> addKey(line, value, 1f, size = 19f) { typeText(value) } }
            repeat(10 - keys.size) { line.addView(View(this), LinearLayout.LayoutParams(0, dp(46), 1f)) }
            body.addView(line)
        }
        val tabs = row()
        tabs.addView(key("‹", 20f, colors.function, colors.text) { fullSymbols = false; render() }, LinearLayout.LayoutParams(dp(34), dp(44)))
        val scroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val items = row()
        ReferenceSymbolsCatalog.categories.forEachIndexed { index, item -> items.addView(key(item.name, 12f, colors.key, if (index == symbolCategory) colors.accent else colors.text) { symbolCategory = index; render() }, LinearLayout.LayoutParams(dp(62), dp(44))) }
        scroll.addView(items); tabs.addView(scroll, LinearLayout.LayoutParams(0, dp(44), 1f))
        tabs.addView(key("⌫", 20f, colors.function, colors.text) { delete() }.also { enableHeldDelete(it) }, LinearLayout.LayoutParams(dp(38), dp(44)))
        body.addView(tabs)
        scroll.post { scroll.scrollTo(dp(62) * symbolCategory, 0) }
    }

    private fun addCharacterRow(characters: String, inset: Int) {
        val line = row().apply { setPadding(dp(inset), 0, dp(inset), 0) }
        characters.forEach { character -> addKey(line, if (chinese && character.isLetter()) character.uppercaseChar().toString() else character.toString(), 1f) { typeText(character.toString()) } }
        body.addView(line)
    }

    private fun addKey(parent: LinearLayout, text: String, weight: Float, function: Boolean = false, accent: Boolean = false,
                       size: Float = 21f, action: () -> Unit): TextView {
        val view = key(text, size, if (accent) colors.accent else if (function) colors.function else colors.key,
            if (accent) Color.WHITE else colors.text, action)
        parent.addView(view, LinearLayout.LayoutParams(0, dp(46), weight).apply { setMargins(dp(2), dp(3), dp(2), dp(3)) })
        return view
    }

    private fun renderReplyStrip() {
        val strip = row().apply {
            background = rounded(Color.rgb(229, 230, 236))
            setPadding(dp(7), dp(7), dp(7), dp(7))
        }
        val card = row().apply { background = rounded(Color.WHITE) }
        card.addView(label("AI", 11f, Color.rgb(153, 158, 243)), LinearLayout.LayoutParams(dp(24), dp(38)))
        replyScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        resultsColumn = row()
        replyScroll!!.addView(resultsColumn)
        card.addView(replyScroll, LinearLayout.LayoutParams(0, dp(38), 1f))
        strip.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38)))
        root.addView(strip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))
    }

    private fun renderAi() {
        val message = row()
        sourcePreview = label(source.ifBlank { if (task == ReplyTask.OPENER) "写下想聊的话题" else "复制 TA 的话，自动导入" }, 16f, colors.accent).apply {
            maxLines = 2; setPadding(dp(10), dp(3), dp(10), dp(3))
            background = rounded(colors.key); contentDescription = "消息内容，点此编辑"
            setOnClickListener { editSource() }
        }
        message.addView(sourcePreview, LinearLayout.LayoutParams(0, dp(48), 1f).apply { setMargins(dp(1), dp(3), dp(4), dp(4)) })
        val recognize = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            background = rounded(colors.accent); contentDescription = "截图识别"; isClickable = true
            addView(KeyboardIconView(this@SmartKeyImeService, "ocr", Color.WHITE), LinearLayout.LayoutParams(dp(34), dp(30)))
            addView(label("读懂TA", 10f, Color.WHITE))
            setOnClickListener { showInlineGallery() }
        }
        message.addView(recognize, LinearLayout.LayoutParams(dp(58), dp(48)).apply { setMargins(0, dp(3), dp(1), dp(4)) })
        body.addView(message)
        val board = row().apply { gravity = Gravity.TOP }
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ReferenceKeyboardCatalog.replyPersonas.chunked(3).forEach { roles ->
            val line = row()
            roles.forEach { persona ->
                line.addView(key(persona.emoji + persona.name, 13f, colors.key, colors.text) {
                    settings = settings.copy(personaId = persona.id); saveSettings(); invalidateResults(); render()
                    generate()
                }.apply { maxLines = 1; contentDescription = persona.name },
                    LinearLayout.LayoutParams(0, dp(44), 1f).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) })
            }
            repeat(3 - roles.size) { line.addView(View(this), LinearLayout.LayoutParams(0, dp(44), 1f)) }
            grid.addView(line)
        }
        board.addView(ScrollView(this).apply { addView(grid) }, LinearLayout.LayoutParams(0, dp(194), 1f))
        val actions = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val delete = icon("delete", "删除草稿文字", colors.text) { target = Target.HOST; delete() }.apply { background = rounded(colors.function) }
        actions.addView(delete, LinearLayout.LayoutParams(-1, dp(46)).apply { setMargins(dp(2), dp(2), dp(1), dp(2)) })
        actions.addView(key("清空", 16f, colors.function, colors.text) { clearHostDraft() }, LinearLayout.LayoutParams(-1, dp(46)).apply { setMargins(dp(2), dp(2), dp(1), dp(2)) })
        actions.addView(key("发送", 17f, colors.accent, Color.WHITE) { sendReply() }.apply { contentDescription = "发送回复" }, LinearLayout.LayoutParams(-1, dp(96)).apply { setMargins(dp(2), dp(2), dp(1), dp(2)) })
        board.addView(actions, LinearLayout.LayoutParams(dp(58), ViewGroup.LayoutParams.WRAP_CONTENT))
        body.addView(board)
        statusLabel = label(status, 10f, colors.muted).apply {
            gravity = Gravity.CENTER_VERTICAL; maxLines = 2; setPadding(dp(5), 0, dp(4), 0)
        }
        body.addView(statusLabel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(28)))
        renderCandidates()
    }

    private fun renderCandidates() {
        val column = resultsColumn ?: return
        column.removeAllViews()
        if (candidates.isEmpty()) {
            column.addView(label(if (loading) "正在生成建议…" else "选择回复方式，让表达更像你", 13f, Color.rgb(94, 98, 119)).apply {
                gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), 0, dp(8), 0)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)))
            return
        }
        selectedCandidate = selectedCandidate.coerceIn(candidates.indices)
        val width = (resources.displayMetrics.widthPixels - dp(54)).coerceAtLeast(dp(180))
        candidates.forEachIndexed { index, candidate ->
            val preview = label("${index + 1} · ${candidate.text}", 14f, Color.rgb(32, 33, 43)).apply {
                gravity = Gravity.CENTER_VERTICAL; maxLines = 1; isSingleLine = true
                setPadding(dp(6), 0, dp(8), 0)
                contentDescription = "选择候选 ${index + 1}：${candidate.text}"
                isClickable = true
                setOnClickListener { selectedCandidate = index; insertSelectedReply() }
                setOnLongClickListener { showFullReply(index); true }
                background = rounded(if (index == selectedCandidate) Color.rgb(236, 238, 255) else Color.WHITE)
            }
            column.addView(preview, LinearLayout.LayoutParams(width, dp(38)))
        }
        replyScroll?.post { replyScroll?.scrollTo(selectedCandidate * width, 0) }
    }

    private fun showFullReply(index: Int) {
        val candidate = candidates.getOrNull(index) ?: return
        body.removeAllViews()
        val full = ScrollView(this)
        full.addView(label(candidate.text, 15f, colors.text).apply {
            gravity = Gravity.TOP or Gravity.START; setPadding(dp(12), dp(12), dp(12), dp(12))
            background = rounded(colors.key)
        })
        body.addView(full, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(214)))
        val actions = row()
        actions.addView(key("返回", 14f, colors.function, colors.text) { render() }, LinearLayout.LayoutParams(0, dp(44), 1f))
        actions.addView(key("使用这条", 14f, colors.accent, Color.WHITE) { selectedCandidate = index; insertSelectedReply() }, LinearLayout.LayoutParams(0, dp(44), 1f))
        body.addView(actions)
    }

    private fun readReplyDraft(): ReplyDraftSnapshot {
        val connection = currentInputConnection
        return ReplyDraftSnapshot(connection?.getTextBeforeCursor(8_000, 0)?.toString(),
            connection?.getTextAfterCursor(8_000, 0)?.toString(), connection?.getSelectedText(0)?.toString(), readEditorSelection())
    }

    private fun insertSelectedReply(expectedDraft: ReplyDraftSnapshot? = null): Boolean {
        val candidate = candidates.getOrNull(selectedCandidate) ?: return false
        val token = candidateToken
        if (sensitive || token == null || !sessions.isCurrent(token)) {
            status = "输入框已经变化，请重新生成"; render(); return false
        }
        finishComposition(); target = Target.HOST
        val connection = currentInputConnection ?: run { status = "当前输入框无法写入，请重新点击输入框"; render(); return false }
        val current = readReplyDraft()
        if (current.empty()) { insertedReply = null; insertedReplyToken = null; insertedDraftSnapshot = null }
        val rewriteFirst = expectedDraft != null && task == ReplyTask.REWRITE
        val previous = if (rewriteFirst) null else insertedReply?.takeIf { insertedReplyToken?.let(sessions::isSameSession) == true }
        if (!current.readable()) {
            status = "当前应用无法读取草稿，请手动复制候选回复"; render(); return false
        }
        if ((previous != null && (current != insertedDraftSnapshot || !ReplyDraftGuard.canReplaceInserted(previous, current))) ||
            (expectedDraft != null && current != expectedDraft)) {
            status = "输入框内容已编辑，保留你的文字；可清空草稿后再生成"
            render(); return false
        }
        if (previous == candidate.text) {
            status = "已填入第 ${selectedCandidate + 1} 条，确认后点发送"; render(); return true
        }
        connection.beginBatchEdit()
        val inserted = try {
            if (rewriteFirst && current.selection.isNullOrEmpty()) {
                val extracted = connection.getExtractedText(ExtractedTextRequest(), 0)
                val full = current.before.orEmpty() + current.after.orEmpty()
                if (extracted == null || extracted.startOffset != 0 || extracted.text.toString() != full || full != source ||
                    !connection.setSelection(0, full.length) || connection.getSelectedText(0)?.toString().orEmpty() != full) false
                else connection.commitText(candidate.text, 1)
            } else if (previous != null && !connection.deleteSurroundingText(previous.length, 0)) false
            else connection.commitText(candidate.text, 1)
        } finally { connection.endBatchEdit() }
        if (inserted) { insertedReply = candidate.text; insertedReplyToken = token; insertedDraftSnapshot = readReplyDraft() }
        status = if (inserted) "已自动填入第 ${selectedCandidate + 1} 条，确认后点发送" else "当前输入框无法写入，请重新点击输入框后再试"
        render()
        return inserted
    }

    private fun sendReply() {
        if (sensitive) {
            status = "输入框已经变化，请重新生成"; render(); return
        }
        finishComposition(); target = Target.HOST
        val connection = currentInputConnection ?: return
        if (connection.getTextBeforeCursor(8_000, 0).isNullOrEmpty() && connection.getTextAfterCursor(8_000, 0).isNullOrEmpty() && connection.getSelectedText(0).isNullOrEmpty()) {
            status = "输入框里没有待发送文字"; render(); return
        }
        cancelPending()
        val handled = connection.performEditorAction(EditorInfo.IME_ACTION_SEND)
        status = if (handled) "已请求发送，请查看聊天结果" else "当前应用不支持键盘发送，请点击聊天中的发送按钮"
        if (handled) {
            insertedReply = null; insertedReplyToken = null; insertedDraftSnapshot = null
            invalidateResults()
        }
        render()
    }

    private fun overlayPalette() = ReferenceOverlayViews.Palette(colors.background, colors.key, colors.function, colors.text, colors.muted, colors.accent)

    private fun showAi(selectedTask: ReplyTask) {
        if (sensitive) return
        clearGalleryPermission()
        finishComposition(); target = Target.HOST
        if (task == ReplyTask.REPLY) replySource = source
        if (task != selectedTask) { invalidateResults(); task = selectedTask }
        panel = Panel.AI
        when (task) {
            ReplyTask.REWRITE -> { val draft = readReplyDraft(); source = draft.selection?.takeIf { it.isNotEmpty() } ?: (draft.before.orEmpty() + draft.after.orEmpty()).take(8_000) }
            ReplyTask.REPLY -> { source = replySource; importClipboard(automatic = true) }
            else -> Unit
        }
        render()
    }

    private fun showRelationships() {
        render(); body.removeAllViews()
        body.addView(ReferenceOverlayViews.relationships(this, overlayPalette(), relationship, { relationship = it; invalidateResults(); render() }, { render() }), LinearLayout.LayoutParams(-1, dp(258)))
    }

    private fun showTools() {
        if (sensitive) return
        finishComposition(); target = Target.HOST; panel = Panel.AI
        render(); body.removeAllViews()
        body.addView(ReferenceOverlayViews.more(this, overlayPalette(), { action ->
            when(action) {
                "switch_input_method" -> showInputMethods()
                "keyboard_skins" -> { body.removeAllViews(); body.addView(ReferenceOverlayViews.skins(this, overlayPalette(), KeyboardPreferences.theme(this), { KeyboardPreferences.setTheme(this, it); showTools() }, { showTools() }), LinearLayout.LayoutParams(-1, dp(258))) }
                "persona_market", "my_personas" -> openScreen("personas")
                "custom_persona" -> openScreen("preferences")
            }
        }, { showAi(ReplyTask.REPLY) }), LinearLayout.LayoutParams(-1, dp(258)))
    }

    private fun showInlineGallery() {
        if (sensitive) return
        finishComposition(); target = Target.HOST; cancelPending()
        render(); body.removeAllViews()
        gallery = KeyboardGalleryView(this, overlayPalette(), { clearGalleryPermission(); gallery?.dispose(); gallery = null; render() }, { images -> clearGalleryPermission(); recognizeGallery(images) }, { requestGalleryPermission() })
        body.addView(gallery, LinearLayout.LayoutParams(-1, dp(385)))
        gallery?.refresh()
    }

    private fun clearGalleryPermission() {
        GalleryPermissionBridge.cancel(permissionId); permissionId = null; permissionEditor = null; permissionReady = false; permissionExpires = 0
    }

    private fun requestGalleryPermission() {
        val editor = editorIdentity ?: return
        clearGalleryPermission()
        permissionEditor = editor; permissionReady = false; permissionExpires = SystemClock.elapsedRealtime() + 60_000
        var requestId: String? = null
        requestId = GalleryPermissionBridge.begin { _, error ->
            if (permissionId != requestId) return@begin
            permissionReady = true
            main.postDelayed({
                if (permissionId != null && permissionReady && editorIdentity == editor && !sensitive) {
                    showInlineGallery()
                    if (error != null) status = error
                    if (Build.VERSION.SDK_INT >= 28) requestShowSelf(0)
                }
            }, 400)
        }
        permissionId = requestId
        try { startActivity(Intent(this, GalleryPermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("request_id", permissionId)) }
        catch (_: Exception) { GalleryPermissionBridge.cancel(permissionId); permissionId = null; permissionEditor = null; status = "无法打开相册授权"; render() }
    }

    private fun recognizeGallery(images: List<KeyboardGalleryImage>) {
        gallery?.dispose(); gallery = null
        cancelPending()
        val token = sessions.startGeneration()
        status = "正在本机识别 ${images.size} 张截图…"; render()
        pending = worker.submit {
            val parts = ArrayList<String>()
            var failure: String? = null
            for ((index, image) in images.take(3).withIndex()) {
                if (Thread.currentThread().isInterrupted) return@submit
                val latch = java.util.concurrent.CountDownLatch(1)
                var recognized: String? = null
                var recognitionError: String? = null
                OcrImporter.recognize(this, image.uri) { text, error -> recognized = text; recognitionError = error; latch.countDown() }
                if (!latch.await(30, java.util.concurrent.TimeUnit.SECONDS)) { failure = "图片识别超时"; break }
                if (recognitionError != null) failure = recognitionError
                if (!recognized.isNullOrBlank()) parts.add("截图${index + 1}：\n$recognized")
            }
            val text = parts.joinToString("\n\n").take(8_000)
            main.post {
                if (!sessions.isCurrent(token) || sensitive) return@post
                if (text.isBlank()) { status = failure ?: "没有识别到文字，请选择清晰截图"; render(); return@post }
                source = text; replySource = text; task = ReplyTask.REPLY; panel = Panel.AI
                status = "已识别 ${parts.size} 张截图，确认内容后分析或回复"
                showOcrPreview(text, token)
            }
        }
    }

    private fun showOcrPreview(text: String, token: EditorSessionGuard.Token) {
        render(); body.removeAllViews()
        body.addView(ScrollView(this).apply { addView(label(text, 14f, colors.text).apply { gravity = Gravity.TOP or Gravity.START; setPadding(dp(10), dp(8), dp(10), dp(8)) }) }, LinearLayout.LayoutParams(-1, dp(205)))
        val actions = row()
        actions.addView(key("帮你回", 14f, colors.function, colors.text) { render() }, LinearLayout.LayoutParams(0, dp(44), 1f))
        actions.addView(key("读懂TA · 分析", 14f, colors.accent, Color.WHITE) { analyzeScreenshot(text, token) }, LinearLayout.LayoutParams(0, dp(44), 1f))
        body.addView(actions)
    }

    private fun analyzeScreenshot(text: String, previousToken: EditorSessionGuard.Token) {
        if (!sessions.isCurrent(previousToken) || sensitive || !loadSettings()) { status = "请检查模型设置或重新选择截图"; render(); return }
        cancelPending(); val token = sessions.startGeneration(); val snapshot = settings
        status = "正在分析截图文字…"; render()
        pending = worker.submit {
            try {
                val result = AiClient().generate(snapshot, ReplyRequest(text, ReplyTask.ANALYZE))
                main.post {
                    if (!sessions.isCurrent(token) || sensitive) return@post
                    render(); body.removeAllViews()
                    val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                    result.forEach { candidate -> column.addView(label(candidate.title + "\n" + candidate.text, 14f, colors.text).apply { gravity = Gravity.START; setPadding(dp(10), dp(8), dp(10), dp(8)) }) }
                    body.addView(ScrollView(this).apply { addView(column) }, LinearLayout.LayoutParams(-1, dp(218)))
                    body.addView(key("根据截图帮你回", 14f, colors.accent, Color.WHITE) { task = ReplyTask.REPLY; source = text; replySource = text; render() }, LinearLayout.LayoutParams(-1, dp(44)))
                }
            } catch (error: Exception) { main.post { if (sessions.isCurrent(token)) { status = (error as? AiException)?.message ?: "分析失败，请重试"; showOcrPreview(text, token) } } }
        }
    }

    private fun openScreen(screen: String) {
        finishComposition(); cancelPending()
        startActivity(Intent(this, dev.lovekey.open.ui.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("screen", screen))
    }

    private fun cycleIntimacy() {
        if (sensitive) return
        render(); body.removeAllViews()
        body.addView(ReferenceOverlayViews.intimacy(this, overlayPalette(), settings.intimacy, { settings = settings.copy(intimacy = it); saveSettings(); invalidateResults(); render() }, { render() }), LinearLayout.LayoutParams(-1, dp(258)))
    }

    private fun clearHostDraft() {
        finishComposition(); target = Target.HOST; cancelPending()
        val connection = currentInputConnection ?: return
        val draft = readReplyDraft()
        val extracted = connection.getExtractedText(ExtractedTextRequest(), 0)
        if (!draft.readable() || extracted == null || extracted.startOffset != 0 || extracted.text.length > 8_000) { status = "当前应用无法安全清空，请在聊天框清空"; render(); return }
        connection.beginBatchEdit()
        val cleared = try {
            connection.setSelection(0, extracted.text.length) && connection.getSelectedText(0)?.toString().orEmpty() == extracted.text.toString() && connection.commitText("", 1)
        } finally { connection.endBatchEdit() }
        if (cleared) { insertedReply = null; insertedReplyToken = null; insertedDraftSnapshot = null }
        status = if (cleared) "草稿已清空，对方消息保留" else "当前应用无法清空，请在聊天框清空"; render()
    }

    private fun saveSettings() {
        try { repository.save(settings) } catch (error: AiException) { status = error.message ?: "无法保存设置" }
    }

    private fun chooseMemory() {
        val memories = try { repository.listMemories() } catch (error: AiException) { status = error.message ?: "无法读取记忆"; render(); return }
        val choices = listOf<ContactMemory?>(null) + memories
        val next = (choices.indexOfFirst { it?.id == selectedMemory?.id } + 1) % choices.size
        selectedMemory = choices[next]
        status = if (memories.isEmpty()) "可在应用设置里添加联系人记忆" else "本次使用：${selectedMemory?.name ?: "不使用记忆"}（再点切换）"
        invalidateResults(); render()
    }

    private fun pasteSource() {
        clearImageImport()
        importClipboard(automatic = false)
        render()
    }

    private fun importClipboard(automatic: Boolean) {
        if (sensitive) return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = try {
            val item = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
            (item?.text ?: item?.htmlText)?.toString().orEmpty().take(8_000)
        } catch (_: SecurityException) {
            status = "无法读取剪贴板，可输入消息或导入当前文字"
            return
        }
        // Reopening the same task must preserve edits and existing results.
        if (automatic && text == lastImportedClipboard) return
        lastImportedClipboard = text
        if (text.isBlank()) {
            if (source.isBlank() || !automatic) status = "剪贴板里没有文字，可输入消息或导入当前文字"
            return
        }
        source = text
        status = if (automatic) "已自动导入 · 选择回复方式或点生成" else "已粘贴，可点消息内容编辑"
        invalidateResults()
    }

    private fun importEditorText() {
        clearImageImport()
        if (sensitive) return
        finishComposition()
        val connection = currentInputConnection
        val selected = connection?.getSelectedText(0)?.toString().orEmpty()
        val text = if (selected.isNotBlank()) selected else {
            connection?.getTextBeforeCursor(4_000, 0)?.toString().orEmpty() + connection?.getTextAfterCursor(4_000, 0)?.toString().orEmpty()
        }
        source = text.take(8_000)
        status = if (source.isBlank()) "当前输入框没有可读取文字，请主动粘贴" else "已导入当前输入文字，可编辑后生成"
        invalidateResults(); render()
    }

    private fun editSource() {
        clearImageImport()
        if (sensitive) return
        finishComposition(); invalidateResults(); target = Target.SOURCE; panel = Panel.KEYBOARD; symbols = false; render()
    }

    private fun clearSource() { clearImageImport(); source = ""; invalidateResults(); status = "消息已清空"; render() }

    private fun generate() {
        clearImageImport()
        if (sensitive) return
        finishComposition()
        if (!loadSettings()) { render(); return }
        if (source.isBlank()) { status = "先粘贴或输入消息 / 任务内容"; render(); return }
        cancelPending()
        val token = sessions.startGeneration()
        selectedCandidate = 0
        val originalDraft = readReplyDraft()
        val request = ReplyRequest(text = source, task = task, context = if (task == ReplyTask.OPENER) openerContext else if (task == ReplyTask.REWRITE) "沟通关系：$relationship" else "",
            contactMemory = selectedMemory?.let { "${it.name}：${it.notes}" }.orEmpty(),
            targetLanguage = if (task == ReplyTask.TRANSLATE) targetLanguage else "中文")
        val snapshot = settings
        loading = true; candidates = emptyList(); candidateToken = null
        status = if (snapshot.demoMode) "演示模式 · 以下为模板示例" else "正在请求 ${snapshot.model.ifBlank { "已配置模型" }}…"
        render()
        pending = worker.submit {
            try {
                val result = AiClient().generate(snapshot, request)
                main.post {
                    if (sessions.isCurrent(token) && !sensitive) {
                        loading = false; candidates = result; candidateToken = token
                        status = "生成完成 · 长按查看全文"
                        insertSelectedReply(originalDraft)
                        if (panel == Panel.AI) render()
                    }
                }
            } catch (error: Exception) {
                main.post {
                    if (sessions.isCurrent(token) && !sensitive) {
                        loading = false; candidates = emptyList()
                        status = (error as? AiException)?.message ?: "生成失败，请检查模型设置后重试"
                        if (panel == Panel.AI) render()
                    }
                }
            }
        }
    }

    private fun invalidateResults() { cancelPending(); candidates = emptyList(); candidateToken = null; selectedCandidate = 0 }

    private fun cancelPending() {
        sessions.cancelGeneration()
        pending?.cancel(true); pending = null; loading = false
    }

    private fun typeText(text: String) {
        ensureCompositionSelection()
        if (chinese && !symbols && !sensitive && text.length == 1 && (text[0] in 'a'..'z' || text == "'")) {
            if (!engine.input(text[0])) { finishComposition(); if (!engine.input(text[0])) commit(text) }
            if (target == Target.HOST && engine.raw.isNotEmpty()) writeHostComposition()
            refreshComposition()
        } else {
            finishComposition(); commit(text)
        }
    }

    private fun choosePinyin(index: Int, fallback: Boolean = false) {
        if (!ensureCompositionSelection()) {
            if (fallback) commit(" ")
            return
        }
        if (engine.raw.isEmpty()) return
        if (index < 0 || engine.candidates.isEmpty()) {
            if (fallback) finishComposition()
            return
        }
        val selected = engine.choose(index)
        if (selected != null) commit(selected) else if (target == Target.HOST) writeHostComposition()
        refreshComposition()
    }

    private fun finishComposition() {
        if (!ensureCompositionSelection()) return
        val text = engine.commitBest() ?: return
        commit(text)
        currentInputConnection?.takeIf { target == Target.HOST }?.finishComposingText()
        refreshComposition()
    }

    private fun commit(text: String) {
        if (target == Target.SOURCE && !sensitive) {
            source = (source + text).take(8_000)
            sourcePreview?.text = source
        } else {
            clearCompositionTracking()
            currentInputConnection?.commitText(text, 1)
        }
    }

    private fun delete() {
        ensureCompositionSelection()
        if (engine.raw.isNotEmpty()) {
            engine.backspace()
            if (target == Target.HOST) writeHostComposition()
            refreshComposition()
        } else if (target == Target.SOURCE && !sensitive) {
            source = InputSafety.dropLastCodePoint(source); sourcePreview?.text = source
        } else {
            val connection = currentInputConnection ?: return
            if (!connection.getSelectedText(0).isNullOrEmpty()) connection.commitText("", 1)
            else if (!connection.deleteSurroundingTextInCodePoints(1, 0)) {
                connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
                connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
            }
        }
    }

    private fun refreshComposition() {
        composingLabel?.text = if (engine.raw.isEmpty()) {
            if (engine.ready) "中文全拼 · 点候选选词" else "中文引擎未加载 · 可切换英文"
        } else engine.composing
        if (target == Target.SOURCE) sourcePreview?.text = source + engine.composing
        candidateRow?.let { row ->
            row.removeAllViews()
            engine.candidates.forEachIndexed { position, candidate ->
                row.addView(key(candidate.text, 17f, colors.background, if (position == 0) colors.accent else colors.text) { choosePinyin(candidate.index) },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(32)).apply { setMargins(dp(4), 0, dp(4), 0) })
            }
        }
    }

    private fun writeHostComposition() {
        if (sensitive || target != Target.HOST) return
        selectionCheck?.let(main::removeCallbacks); selectionCheck = null
        val connection = currentInputConnection ?: return
        if (connection.setComposingText(engine.composing, 1)) {
            compositionSelection.recordWrite(readEditorSelection())
        } else compositionSelection.clear()
        if (engine.raw.isEmpty()) {
            connection.finishComposingText()
            clearCompositionTracking()
        }
    }

    private fun readEditorSelection(): Pair<Int, Int>? {
        if (sensitive) return null
        val request = ExtractedTextRequest().apply { hintMaxChars = 160; hintMaxLines = 2 }
        val extracted = currentInputConnection?.getExtractedText(request, 0) ?: return null
        if (extracted.selectionStart < 0 || extracted.selectionEnd < 0) return null
        return Pair(extracted.startOffset + extracted.selectionStart, extracted.startOffset + extracted.selectionEnd)
    }

    private fun clearCompositionTracking() {
        compositionSelection.clear()
        selectionCheck?.let(main::removeCallbacks); selectionCheck = null
    }

    private fun ensureCompositionSelection(): Boolean {
        if (target != Target.HOST || sensitive || engine.raw.isEmpty()) return true
        // This synchronous checkpoint also covers a cursor move followed by a
        // key press before the delayed selection callback can run. Do it before
        // modifying the decoder or adopting a new write position.
        if (!compositionSelection.hasMoved(readEditorSelection())) return true
        engine.reset()
        clearCompositionTracking()
        currentInputConnection?.finishComposingText()
        refreshComposition()
        return false
    }

    private fun enter() {
        if (target == Target.SOURCE) { finishComposition(); target = Target.HOST; panel = Panel.AI; render(); return }
        // Enter keeps its normal editor behavior; AI candidates never trigger it.
        finishComposition()
        val info = currentInputEditorInfo
        val action = (info?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
        val noAction = (info?.imeOptions ?: 0) and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0
        if (!noAction && action !in setOf(EditorInfo.IME_ACTION_NONE, EditorInfo.IME_ACTION_UNSPECIFIED)) {
            currentInputConnection?.performEditorAction(action)
        } else currentInputConnection?.commitText("\n", 1)
    }

    private fun enterLabel(): String {
        if (target == Target.SOURCE) return "完成"
        val info = currentInputEditorInfo
        if ((info?.imeOptions ?: 0) and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0) return "换行"
        return when ((info?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_SEND -> "发送"
            EditorInfo.IME_ACTION_SEARCH -> "搜索"
            EditorInfo.IME_ACTION_GO -> "前往"
            EditorInfo.IME_ACTION_NEXT -> "下一项"
            EditorInfo.IME_ACTION_DONE -> "完成"
            else -> "换行"
        }
    }

    private fun enableHeldDelete(view: View) {
        var cancelledTouch = false
        view.setOnLongClickListener { deleteHeld = true; main.post(deleteRepeat); true }
        view.setOnTouchListener { pressedView, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> { cancelledTouch = false; false }
                android.view.MotionEvent.ACTION_MOVE -> {
                    if (event.x < 0 || event.y < 0 || event.x > pressedView.width || event.y > pressedView.height) {
                        cancelledTouch = true; stopDeleteRepeat()
                    }
                    false
                }
                android.view.MotionEvent.ACTION_UP -> {
                    val repeated = deleteHeld
                    stopDeleteRepeat()
                    pressedView.isPressed = false
                    pressedView.cancelLongPress()
                    if (!repeated && !cancelledTouch) pressedView.performClick()
                    // Consume UP: the explicit accessible click above must occur only once.
                    true
                }
                android.view.MotionEvent.ACTION_CANCEL -> {
                    cancelledTouch = true; stopDeleteRepeat()
                    pressedView.isPressed = false; pressedView.cancelLongPress(); true
                }
                else -> false
            }
        }
    }

    private fun stopDeleteRepeat() { deleteHeld = false; main.removeCallbacks(deleteRepeat) }

    private fun showInputMethods() { (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker() }

    private fun clearImageImport() {
        KeyboardImageImport.cancel(imageImport?.id)
        imageImport = null
    }

    private fun consumeImageImport(): Boolean {
        val ticket = imageImport ?: return false
        if (!ticket.ready) return false
        if (sensitive || SystemClock.elapsedRealtime() > ticket.expiresAt) {
            clearImageImport()
            return false
        }
        if (editorIdentity != ticket.editor) return false
        cancelPending()
        panel = Panel.AI
        task = ReplyTask.REPLY
        target = Target.HOST
        selectedMemory = ticket.memory
        candidates = emptyList()
        candidateToken = null
        if (!ticket.text.isNullOrBlank() && ticket.error == null) {
            source = ticket.text!!.take(8_000)
            status = "已识别 · 点消息内容可编辑，确认本次聊天内容后再生成"
        } else {
            status = ticket.error ?: "没有识别到文字，请换一张清晰截图"
        }
        ticket.applied = true
        return true
    }

    private fun selectKeyboardImage() {
        if (sensitive) return
        val editor = editorIdentity ?: return
        finishComposition()
        invalidateResults()
        clearImageImport()
        val pickerPackage = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "image/*"; addCategory(Intent.CATEGORY_OPENABLE)
        }.resolveActivity(packageManager)?.packageName
        val ticket = ImageImport(editor, selectedMemory, pickerPackage)
        imageImport = ticket
        ticket.id = KeyboardImageImport.begin { text, error ->
            main.post {
                if (imageImport !== ticket) return@post
                ticket.text = text
                ticket.error = error
                ticket.ready = true
                // Allow the transparent picker to finish and the host editor to resume.
                var attempts = 0
                val restore = object : Runnable {
                    override fun run() {
                        if (imageImport !== ticket) return
                        if (SystemClock.elapsedRealtime() > ticket.expiresAt || sensitive) { clearImageImport(); return }
                        if (editorIdentity == ticket.editor) {
                            if (ticket.applied) return
                            if (Build.VERSION.SDK_INT >= 28) requestShowSelf(0)
                            else (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).toggleSoftInput(InputMethodManager.SHOW_IMPLICIT, 0)
                        }
                        if (++attempts < 20) main.postDelayed(this, 200)
                    }
                }
                main.postDelayed(restore, 400)
            }
        }
        status = "选择截图后，识别结果会回到这里"
        render()
        try {
            startActivity(Intent(this, KeyboardImageActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra("request_id", ticket.id)
            })
        } catch (_: Exception) {
            clearImageImport()
            status = "无法打开图片选择器，请稍后再试"
            render()
        }
    }

    private fun openApp() {
        if (sensitive) return
        finishComposition(); cancelPending()
        packageManager.getLaunchIntentForPackage(packageName)?.let { intent ->
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            intent.putExtra("screen", "models")
            intent.putExtra("task", "REPLY")
            startActivity(intent)
        }
    }

    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

    private fun label(text: String, size: Float, color: Int) = TextView(this).apply {
        this.text = text; textSize = size; setTextColor(color); gravity = Gravity.CENTER
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }

    private fun key(text: String, size: Float, background: Int = colors.key, foreground: Int = colors.text, action: () -> Unit): TextView =
        label(text, size, foreground).apply {
            setPadding(dp(7), 0, dp(7), 0); gravity = Gravity.CENTER; isClickable = true; isFocusable = false
            this.background = RippleDrawable(ColorStateList.valueOf(Color.argb(60, 145, 137, 255)), rounded(background), null)
            contentDescription = text
            setOnClickListener {
                if (KeyboardPreferences.haptics(this@SmartKeyImeService)) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                action()
            }
        }

    private fun rounded(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(9).toFloat() }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun palette(name: String) = when (name) {
        "light" -> Palette(Color.rgb(230, 232, 242), Color.WHITE, Color.rgb(211, 216, 235), Color.rgb(31, 32, 47), Color.rgb(102, 108, 129), Color.rgb(103, 101, 239))
        "lavender" -> Palette(Color.rgb(222, 219, 248), Color.rgb(249, 248, 255), Color.rgb(199, 196, 233), Color.rgb(44, 39, 76), Color.rgb(114, 109, 149), Color.rgb(117, 102, 240))
        else -> Palette(Color.rgb(48, 49, 62), Color.rgb(88, 90, 96), Color.rgb(59, 61, 69), Color.rgb(250, 250, 252), Color.rgb(177, 181, 202), Color.rgb(96, 112, 255))
    }
}
