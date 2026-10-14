package dev.lovekey.open.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.widget.*
import dev.lovekey.open.R
import dev.lovekey.open.ai.*
import dev.lovekey.open.data.ContactMemory
import dev.lovekey.open.data.SettingsRepository
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.Future

class MainActivity : Activity() {
    private lateinit var repository: SettingsRepository
    private lateinit var root: LinearLayout
    private lateinit var content: LinearLayout
    private val workers = Executors.newCachedThreadPool()
    private var pending: Future<*>? = null
    private var generation = 0
    private val catalogHandler = Handler(Looper.getMainLooper())
    private var catalogPending: Future<*>? = null
    private var catalogDebounce: Runnable? = null
    private var catalogGeneration = 0
    private var catalogDialog: AlertDialog? = null
    private var screen = "home"
    private var tab = "home"
    private var task = ReplyTask.REPLY
    private var draft = ""
    private var draftContext = ""
    private var targetLanguage = "中文"
    private var memoryId: String? = null
    private var modelEditingProvider: ProviderKind? = null
    private var restoringModelForm = false
    private var sourceInput: EditText? = null
    private var contextInput: EditText? = null
    private var status: TextView? = null
    private var candidates: LinearLayout? = null
    private var generateButton: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = SettingsRepository(this)
        if (savedInstanceState != null) {
            draft = savedInstanceState.getString("draft", "")
            draftContext = savedInstanceState.getString("context", "")
            task = runCatching { ReplyTask.valueOf(savedInstanceState.getString("task", "REPLY")) }.getOrDefault(ReplyTask.REPLY)
            targetLanguage = savedInstanceState.getString("language", "中文")
            memoryId = savedInstanceState.getString("memory")
            screen = savedInstanceState.getString("screen", "home")
            tab = savedInstanceState.getString("tab", "home")
            modelEditingProvider = runCatching { ProviderKind.valueOf(savedInstanceState.getString("modelProvider") ?: "") }.getOrNull()
            restoringModelForm = screen == "models"
        } else {
            screen = intent.getStringExtra("screen") ?: "home"
            task = runCatching { ReplyTask.valueOf(intent.getStringExtra("task") ?: "REPLY") }.getOrDefault(ReplyTask.REPLY)
        }
        navigate(screen)
        if (intent.getBooleanExtra("importImage", false) && savedInstanceState == null) root.post { selectImage() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        captureDraft()
        outState.putString("draft", draft); outState.putString("context", draftContext)
        outState.putString("task", task.name); outState.putString("language", targetLanguage)
        outState.putString("memory", memoryId); outState.putString("screen", screen); outState.putString("tab", tab)
        if (screen == "models") outState.putString("modelProvider", modelEditingProvider?.name)
        super.onSaveInstanceState(outState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // A keyboard shortcut may reopen an already running task. Consume its
        // route here as well as onCreate, without submitting any model request.
        val route = intent.getStringExtra("screen") ?: return
        if (route !in setOf("home", "personas", "keyboard", "models", "preferences", "memory", "assistant", "sandbox")) return
        captureDraft()
        task = runCatching { ReplyTask.valueOf(intent.getStringExtra("task") ?: "REPLY") }.getOrDefault(ReplyTask.REPLY)
        modelEditingProvider = null
        restoringModelForm = false
        navigate(route)
        if (route == "assistant" && intent.getBooleanExtra("importImage", false)) root.post { selectImage() }
    }

    private fun captureDraft() { sourceInput?.let { draft = it.text.toString() }; contextInput?.let { draftContext = it.text.toString() } }
    private fun cancelWork() { generation++; pending?.cancel(true); pending = null }
    private fun cancelCatalog() {
        catalogGeneration++
        catalogDebounce?.let { catalogHandler.removeCallbacks(it) }; catalogDebounce = null
        catalogPending?.cancel(true); catalogPending = null
        catalogDialog?.dismiss(); catalogDialog = null
    }
    override fun onDestroy() { cancelWork(); cancelCatalog(); workers.shutdownNow(); super.onDestroy() }
    @Deprecated("Legacy Activity navigation")
    override fun onBackPressed() { if (screen !in listOf("home", "personas", "keyboard")) navigate(tab) else if (screen != "home") navigate("home") else super.onBackPressed() }

    private fun settings(): AiSettings = try { repository.load() } catch (error: AiException) {
        AlertDialog.Builder(this).setTitle("本机设置需要恢复").setMessage(error.message)
            .setPositiveButton("重置模型设置") { _, _ -> safely { repository.resetSettings(); navigate("models") } }
            .setNegativeButton("稍后", null).show()
        AiSettings()
    }
    private fun memories(): List<ContactMemory> = try { repository.listMemories() } catch (error: AiException) {
        AlertDialog.Builder(this).setTitle("本机记忆需要恢复").setMessage(error.message)
            .setPositiveButton("清除无法读取的记忆") { _, _ -> safely { repository.clearMemories(); navigate("memory") } }
            .setNegativeButton("稍后", null).show()
        emptyList()
    }
    private fun safely(action: () -> Unit) { try { action() } catch (error: AiException) { toast(error.message ?: "操作失败，请重试。") } }
    private fun toast(value: String) = Toast.makeText(this, value, Toast.LENGTH_LONG).show()
    private fun dp(value: Int) = Ui.dp(this, value)

    private fun navigate(destination: String) {
        captureDraft(); cancelWork(); cancelCatalog(); screen = destination
        if (screen != "models") { modelEditingProvider = null; restoringModelForm = false }
        if (screen in listOf("home", "personas", "keyboard")) tab = screen
        sourceInput = null; contextInput = null; status = null; candidates = null; generateButton = null
        root = Ui.column(this).apply { setBackgroundColor(Ui.background) }
        root.setOnApplyWindowInsetsListener { view, insets ->
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            }
            insets
        }
        val scroll = ScrollView(this).apply { isFillViewport = true; clipToPadding = false }
        content = Ui.column(this).apply { setPadding(dp(20), dp(14), dp(20), dp(24)) }
        scroll.addView(content); root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        if (screen in listOf("home", "personas", "keyboard")) addNavigation()
        setContentView(root)
        when (screen) {
            "home" -> home()
            "personas" -> personaScreen()
            "keyboard" -> keyboardScreen()
            "models" -> modelScreen()
            "memory" -> memoryScreen()
            "preferences" -> preferenceScreen()
            "sandbox" -> sandboxScreen()
            "assistant" -> assistantScreen()
            else -> home()
        }
    }

    private fun addNavigation() {
        val bar = Ui.row(this).apply { setPadding(dp(12), dp(7), dp(12), dp(7)); setBackgroundColor(Color.WHITE) }
        listOf(Triple("home", "♡", "首页"), Triple("personas", "✦", "人设"), Triple("keyboard", "▤", "我的键盘")).forEach { (key, icon, title) ->
            val item = Ui.column(this).apply { gravity = Gravity.CENTER; setPadding(0, dp(4), 0, dp(4)) }
            item.addView(Ui.text(this, icon, 25f, if (tab == key) Ui.accent else Ui.muted, true).apply { gravity = Gravity.CENTER })
            item.addView(Ui.text(this, title, 12f, if (tab == key) Ui.accent else Ui.muted, tab == key).apply { gravity = Gravity.CENTER })
            item.isClickable = true; item.isFocusable = true; item.contentDescription = title
            item.setOnClickListener { navigate(key) }; bar.addView(item, LinearLayout.LayoutParams(0, dp(60), 1f))
        }
        root.addView(bar)
    }
    private fun title(value: String, subtitle: String? = null, back: Boolean = false) {
        if (back) add(Ui.button(this, "‹ 返回") { navigate(tab) }, bottom = 10)
        add(Ui.text(this, value, 28f, bold = true), bottom = 5)
        subtitle?.let { add(Ui.text(this, it, 13f, Ui.muted), bottom = 18) }
    }
    private fun add(view: View, parent: LinearLayout = content, bottom: Int = 12, height: Int = -2) {
        parent.addView(view, LinearLayout.LayoutParams(-1, if (height < 0) height else dp(height)).apply { bottomMargin = dp(bottom) })
    }
    private fun card(parent: LinearLayout = content, action: (LinearLayout) -> Unit) {
        val body = Ui.column(this).apply { setPadding(dp(16), dp(16), dp(16), dp(16)); background = Ui.round(this@MainActivity, Color.WHITE, 24) }
        action(body); add(body, parent, 14)
    }
    private fun note(value: String, parent: LinearLayout = content) = add(Ui.text(this, value, 12f, Ui.muted), parent, 14)
    private fun field(label: String, value: String = "", multiline: Boolean = false, id: Int? = null, parent: LinearLayout = content, limit: Int = 8000): EditText {
        add(Ui.text(this, label, 13f, Ui.muted, true), parent, 6)
        val edit = EditText(this).apply {
            setText(value); textSize = 15f; setTextColor(Ui.ink); setHintTextColor(Ui.muted)
            background = Ui.round(this@MainActivity, Color.rgb(245, 246, 252), 14)
            setPadding(dp(13), dp(12), dp(13), dp(12)); gravity = Gravity.TOP
            filters = arrayOf(InputFilter.LengthFilter(limit))
            inputType = InputType.TYPE_CLASS_TEXT or if (multiline) InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES else 0
            if (multiline) { minLines = 3; maxLines = 6 } else setSingleLine(true)
            if (id != null) this.id = id
        }
        add(edit, parent, 12); return edit
    }
    private fun choice(parent: LinearLayout, labels: List<String>, selected: Int, changed: (Int) -> Unit): Spinner {
        val spinner = Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, labels); setSelection(selected.coerceIn(0, labels.lastIndex)) }
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, position: Int, id: Long) { changed(position) }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        add(spinner, parent, 12, 48); return spinner
    }

    private fun home() {
        content.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFFDEE2FF.toInt(), Ui.background))
        val current = settings()
        val heading = Ui.row(this)
        heading.addView(Ui.text(this, "SmartKey", 22f, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
        heading.addView(Ui.button(this, "OPEN · 自选模型") { navigate("models") })
        add(heading, bottom = 26)
        add(Ui.text(this, "让每一次表达，刚刚好", 15f, Ui.muted).apply { gravity = Gravity.CENTER }, bottom = 16)
        add(Ui.text(this, "${current.intimacy}%", 64f, bold = true).apply { gravity = Gravity.CENTER }, bottom = 0)
        add(Ui.text(this, "聊天亲密度 · 由你设定", 14f, Ui.muted).apply { gravity = Gravity.CENTER }, bottom = 8)
        val heart = HeartView(this, current.intimacy)
        add(heart, bottom = 8, height = 168)
        val relationship = Ui.text(this, relationship(current.intimacy), 13f, Ui.accent).apply { gravity = Gravity.CENTER }
        add(relationship, bottom = 2)
        val slider = SeekBar(this).apply { max = 100; progress = current.intimacy }
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, progress: Int, user: Boolean) { relationship.text = relationship(progress) }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) { safely { repository.save(settings().copy(intimacy = slider.progress)); navigate("home") } }
        })
        add(slider, bottom = 16)
        add(Ui.button(this, "启用 SmartKey 键盘", true) { activate() }, height = 56)
        card { body ->
            add(Ui.text(this, "你的模型，你的表达", 17f, bold = true), body, 6)
            note(if (current.demoMode) "演示模式已开启 · 模板预览，不连接模型" else if (current.model.isBlank()) "配置自己的模型，开始生成回复" else "当前模型：${current.model}", body)
            add(Ui.button(this, "打开聊天体验  →", true) { navigate("assistant") }, body, 0)
        }
        val features = Ui.row(this)
        listOf("✦  聊天人设" to "personas", "▤  模型与设置" to "keyboard").forEach { (label, route) ->
            features.addView(Ui.button(this, label) { navigate(route) }, LinearLayout.LayoutParams(0, dp(60), 1f).apply { setMargins(dp(3), 0, dp(3), 0) })
        }
        add(features)
    }
    private fun relationship(level: Int) = when { level < 25 -> "初次认识 · 礼貌克制"; level < 55 -> "自然朋友 · 轻松真诚"; level < 80 -> "逐渐熟悉 · 温柔关心"; else -> "亲密关系 · 先确认彼此边界" }
    private fun activate() {
        AlertDialog.Builder(this).setTitle("启用 SmartKey")
            .setMessage("先在系统中启用 SmartKey，再选择它为当前输入法。")
            .setPositiveButton("1. 启用键盘") { _, _ -> startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
            .setNeutralButton("2. 选择键盘") { _, _ -> (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker() }
            .setNegativeButton("稍后", null).show()
    }

    private fun personaScreen() {
        title("聊天人设", "用适合自己的语气，开启每一段对话")
        card { body -> add(Ui.text(this, "帮你回  /  超会说", 22f, Ui.accent, true), body, 6); note("日常聊天 · 关系升温 · 职场与学术", body) }
        val selected = settings().personaId
        Personas.all.forEachIndexed { index, persona ->
            card { body ->
                val header = Ui.row(this)
                header.addView(Ui.text(this, listOf("☀", "☺", "♡", "✦")[index % 4], 27f, Ui.accent), LinearLayout.LayoutParams(dp(42), -2))
                header.addView(Ui.text(this, persona.name, 19f, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
                header.addView(Ui.text(this, if (selected == persona.id) "已选 ✓" else "选择", 13f, Ui.accent, true))
                add(header, body, 7); note(persona.prompt, body)
                body.isClickable = true; body.isFocusable = true; body.contentDescription = "选择人设：${persona.name}"
                body.setOnClickListener { safely { repository.save(settings().copy(personaId = persona.id)); navigate("personas") } }
            }
        }
        add(Ui.button(this, "+ 自定义人设与 Prompt") { navigate("preferences") })
    }
    private fun keyboardScreen() {
        title("我的键盘", "微信、邮件、浏览器，每个输入框都能用")
        card { body ->
            add(Ui.text(this, "帮你回    超会说    开场白", 18f, Ui.accent, true), body)
            val set = settings(); note("${Personas.find(set.personaId).name} · 亲密度 ${set.intimacy}%", body)
            add(Ui.button(this, "启用 / 切换输入法", true) { activate() }, body, 0)
        }
        listOf(
            Triple("模型设置", "自选云端 / 局域网模型与 API Key", "models"),
            Triple("表达偏好", "自定义 Prompt、语言与键盘主题", "preferences"),
            Triple("联系人记忆", "手动管理，本次使用时由你选择", "memory"),
            Triple("聊天体验", "回复、润色、开场白、邮件、翻译", "assistant"),
            Triple("输入练习", "练习拼音输入与候选插入", "sandbox")
        ).forEach { (label, description, route) -> card { body -> add(Ui.text(this, "$label   ›", 17f, bold = true), body, 5); note(description, body); body.setOnClickListener { navigate(route) } } }
        note("SmartKey 0.1.7\n输入内容不会保存为历史。图片识别在手机本地进行；点生成时，仅将本次文本和已选记忆发送给你配置的模型。")
    }

    private fun assistantScreen() {
        title("聊天体验", "粘贴 TA 的话，选择语气，再挑一条回复", true)
        var current = settings()
        val tasks = listOf("帮你回", "超会说", "开场白", "邮件", "翻译", "读懂TA")
        choice(content, tasks, ReplyTask.entries.indexOf(task)) { position -> task = ReplyTask.entries[position] }
        card { body ->
            sourceInput = field("本次处理的文字", draft, true, R.id.source_text, body).apply { hint = "例如：今天真的好累" }
            val actions = Ui.row(this)
            listOf("粘贴文字" to { pasteSource() }, "截图识别" to { selectImage() }).forEach { (label, action) ->
                actions.addView(Ui.button(this, label, action = action), LinearLayout.LayoutParams(0, dp(48), 1f).apply { setMargins(dp(3), 0, dp(3), 0) })
            }
            add(actions, body, 8)
            note("截图会先识别为文字，请检查并保留需要的部分。", body)
        }
        card { body ->
            add(Ui.text(this, "本次表达", 16f, bold = true), body)
            choice(body, Personas.all.map { it.name }, Personas.all.indexOfFirst { it.id == current.personaId }) { position ->
                if (current.personaId != Personas.all[position].id) safely { current = settings().copy(personaId = Personas.all[position].id); repository.save(current) }
            }
            val contacts = memories()
            choice(body, listOf("不使用联系人记忆") + contacts.map { it.name }, contacts.indexOfFirst { it.id == memoryId } + 1) { position -> memoryId = contacts.getOrNull(position - 1)?.id }
            contextInput = field("补充上下文（可选）", draftContext, true, R.id.context_text, body).apply { hint = "关系、沟通目标或你想补充的信息" }
            val language = field("输出语言", targetLanguage, parent = body, limit = 80)
            language.addTextChangedListener(SimpleTextWatcher { targetLanguage = it.ifBlank { "中文" } })
            note("${relationship(current.intimacy)} · ${current.style}", body)
        }
        status = Ui.text(this, if (current.demoMode) "演示模式 · 下方结果为本地模板" else if (current.model.isBlank()) "请先配置模型，或主动开启演示模式" else "将使用模型：${current.model}", 13f, Ui.accent).apply { id = R.id.generation_status }
        add(status!!)
        generateButton = Ui.button(this, "生成 3 条候选", true) { generate() }.apply { id = R.id.generate_button }
        add(generateButton!!, height = 54)
        add(Ui.button(this, "取消生成") { cancelWork(); status?.text = "已取消"; generateButton?.isEnabled = true })
        candidates = Ui.column(this); add(candidates!!)
        add(Ui.button(this, "模型设置") { navigate("models") })
    }
    private fun pasteSource() {
        val manager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = manager.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        if (text.isBlank()) toast("剪贴板里没有文字。") else {
            sourceInput?.setText(text.take(AiLimits.TEXT)); draft = text.take(AiLimits.TEXT)
            if (text.length > AiLimits.TEXT) toast("已保留前 ${AiLimits.TEXT} 字，请检查后使用。")
        }
    }
    private fun generate() {
        captureDraft()
        val set = settings()
        if (!set.demoMode && set.model.isBlank()) { navigate("models"); return }
        val memory = memories().firstOrNull { it.id == memoryId }
        val request = ReplyRequest(draft, task, draftContext, memory?.let { "联系人：${it.name}\n${it.notes}" }.orEmpty(), targetLanguage)
        cancelWork(); val ticket = generation
        status?.text = if (set.demoMode) "正在生成演示模板…" else "正在等待 ${set.model}…"
        generateButton?.isEnabled = false; candidates?.removeAllViews()
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(sourceInput?.windowToken, 0)
        pending = workers.submit {
            val result = runCatching { AiClient().generate(set, request) }
            runOnUiThread {
                if (generation != ticket || isFinishing || isDestroyed) return@runOnUiThread
                generateButton?.isEnabled = true
                result.fold({ replies ->
                    status?.text = if (set.demoMode) "演示模板 · 不是真实模型生成" else if (request.task == ReplyTask.ANALYZE) "分析完成 · 请结合真实情况判断" else "已生成 · 选择合适的一条"
                    replies.forEach { reply -> card(candidates!!) { body ->
                        add(Ui.text(this, reply.title, 14f, Ui.accent, true), body, 6)
                        add(Ui.text(this, reply.text, 16f).apply { setTextIsSelectable(true) }, body)
                        if (request.task != ReplyTask.ANALYZE) add(Ui.button(this, "复制这条回复") { copy(reply.text); toast("已复制，可返回聊天输入框粘贴。") }, body, 0)
                    } }
                }, { error -> status?.text = (error as? AiException)?.message ?: "生成失败，请检查网络与模型设置。" })
            }
        }
    }
    private fun copy(value: String) { (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("SmartKey 回复", value)) }
    private fun selectImage() { captureDraft(); startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type = "image/*"; addCategory(Intent.CATEGORY_OPENABLE); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }, 41) }
    @Deprecated("Activity image selection result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 41 || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        if (screen != "assistant") navigate("assistant")
        cancelWork(); val ticket = generation; status?.text = "正在手机本地识别图片…"; generateButton?.isEnabled = false
        pending = workers.submit { OcrImporter.recognize(applicationContext, uri) { text, error -> runOnUiThread {
            if (ticket != generation || isFinishing || isDestroyed) return@runOnUiThread
            generateButton?.isEnabled = true
            when {
                error != null -> status?.text = error
                text.isNullOrBlank() -> status?.text = "没有识别到文字，请选择更清晰的截图。"
                else -> { draft = text.take(AiLimits.TEXT); sourceInput?.setText(draft); status?.text = if (text.length > AiLimits.TEXT) "文字较长，已保留前 ${AiLimits.TEXT} 字 · 请检查后再生成" else "已识别 · 请检查文字，只保留本次需要的内容后点生成" }
            }
        } } }
    }

    private fun modelScreen() {
        title("模型设置", "使用你自己的模型与 API Key", true)
        val old = settings(); var provider = modelEditingProvider ?: old.provider
        modelEditingProvider = provider
        lateinit var base: EditText; lateinit var model: EditText; lateinit var key: EditText
        lateinit var catalogStatus: TextView; lateinit var chooseModel: TextView
        var available = emptyList<AvailableModel>()
        var fieldsReady = false
        val spinner = choice(content, ProviderKind.entries.map { it.displayName }, ProviderKind.entries.indexOf(provider)) { index ->
            val next = ProviderKind.entries[index]
            if (provider != next && fieldsReady) { provider = next; modelEditingProvider = next; base.setText(next.defaultBaseUrl); model.setText(""); key.setText("") }
        }
        card { body ->
            base = field("服务地址 Base URL", if (provider == old.provider) old.baseUrl else provider.defaultBaseUrl, id = R.id.base_url, parent = body, limit = 2048).apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI }
            key = field("API Key", if (!restoringModelForm && provider == old.provider) old.apiKey else "", id = R.id.api_key, parent = body, limit = 8192).apply {
                setSingleLine(true)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                transformationMethod = PasswordTransformationMethod.getInstance()
                isSaveEnabled = false; importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            }
            note("密钥加密保存在手机本机。切换接口类型会清空当前输入的密钥，请重新填写。", body)
            if (restoringModelForm) note("页面已恢复。为保护密钥，请重新填写 API Key 后再测试或保存。", body)
            catalogStatus = Ui.text(this, "填写地址和 API Key 后自动获取可用模型。", 13f, Ui.muted).apply { id = R.id.model_catalog_status }
            add(catalogStatus, body)
            chooseModel = Ui.button(this, "选择可用模型") {
                showModelPicker(available, model.text.toString()) { selected -> model.setText(selected.id); toast("已选择 ${selected.id}，保存后生效。") }
            }.apply { id = R.id.choose_model; isEnabled = false }
            add(chooseModel, body)
            model = field("模型名称（也可手动填写）", if (provider == old.provider) old.model else "", id = R.id.model_name, parent = body, limit = 160).apply { hint = "从列表选择，或填写服务端的 model 名称" }
        }
        fieldsReady = true
        fun origin(value: String): String? = runCatching {
            val uri = URI(value.trim())
            val scheme = uri.scheme?.lowercase() ?: return@runCatching null
            val host = uri.host?.lowercase() ?: return@runCatching null
            if (scheme !in setOf("https", "http")) return@runCatching null
            "$scheme://$host:${if (uri.port >= 0) uri.port else if (scheme == "https") 443 else 80}"
        }.getOrNull()
        var credentialOrigin = origin(base.text.toString())
        val catalogSettings = { old.copy(provider = provider, baseUrl = base.text.toString().trim(), apiKey = key.text.toString().trim(), model = "") }
        fun queryCatalog() {
            cancelCatalog()
            val set = catalogSettings()
            val ticket = catalogGeneration
            available = emptyList(); chooseModel.isEnabled = false
            catalogStatus.text = "正在获取可用模型…"
            catalogPending = workers.submit {
                val result = runCatching { ModelCatalogClient().list(set) }
                runOnUiThread {
                    if (ticket != catalogGeneration || screen != "models" || isFinishing || isDestroyed) return@runOnUiThread
                    result.fold({ models ->
                        available = models; chooseModel.isEnabled = models.isNotEmpty()
                        catalogStatus.text = if (models.isEmpty()) "服务未返回可用模型，可手动填写模型名称。" else "发现 ${models.size} 个模型，请点下方选择。" + if (models.size >= 500) " 最多显示 500 个，其它模型可手动填写。" else ""
                        chooseModel.text = if (models.isEmpty()) "选择可用模型" else "选择可用模型（${models.size}）"
                    }, { error ->
                        catalogStatus.text = ((error as? AiException)?.message ?: "获取模型失败，请检查地址与网络。") + " 可手动填写模型名称。"
                    })
                }
            }
        }
        fun scheduleCatalog() {
            cancelCatalog(); available = emptyList(); chooseModel.isEnabled = false; chooseModel.text = "选择可用模型"
            val set = catalogSettings()
            if (set.baseUrl.isBlank()) { catalogStatus.text = "请填写服务地址。"; return }
            val local = runCatching { URI(set.baseUrl).host?.let { EndpointPolicy.isLocalHost(it) } ?: false }.getOrDefault(false)
            val needsKey = set.provider == ProviderKind.ANTHROPIC || set.provider == ProviderKind.GEMINI || (set.provider == ProviderKind.OPENAI_COMPATIBLE && !local)
            if (needsKey && set.apiKey.isBlank()) {
                catalogStatus.text = "填写 API Key 后自动获取模型。"; return
            }
            catalogStatus.text = "即将获取可用模型…"
            val action = Runnable { queryCatalog() }
            catalogDebounce = action; catalogHandler.postDelayed(action, 1200)
        }
        add(Ui.button(this, "刷新模型列表") { queryCatalog() }.apply { id = R.id.refresh_models }, bottom = 9)
        note("目录由所填服务提供；列表中的模型是否可调用仍取决于账号权限和额度。服务不支持目录时可手动填写。")
        add(Ui.text(this, "快速填写地址", 14f, bold = true))
        val presets = listOf(Triple("DeepSeek", ProviderKind.OPENAI_COMPATIBLE, "https://api.deepseek.com/v1"), Triple("Qwen", ProviderKind.OPENAI_COMPATIBLE, "https://dashscope.aliyuncs.com/compatible-mode/v1"), Triple("Ollama", ProviderKind.OLLAMA, "http://127.0.0.1:11434"), Triple("vLLM / 自建", ProviderKind.OPENAI_COMPATIBLE, "http://192.168.1.100:8000/v1"))
        presets.forEach { (label, kind, address) -> add(Ui.button(this, label) {
            provider = kind; modelEditingProvider = kind; spinner.setSelection(ProviderKind.entries.indexOf(kind)); base.setText(address); model.setText(""); key.setText("")
        }, bottom = 7) }
        note("手机的 127.0.0.1 指手机本身。电脑本地模型请填写可访问的局域网 IP。公网服务使用 HTTPS；局域网 IP 可使用 HTTP。")
        val demo = Switch(this).apply { text = "演示模式（本地模板）"; isChecked = old.demoMode; minHeight = dp(48) }
        add(demo)
        note("演示模式用于体验操作，不会请求真实模型。")
        val read = { old.copy(provider = provider, baseUrl = base.text.toString().trim(), model = model.text.toString().trim(), apiKey = key.text.toString().trim(), demoMode = demo.isChecked) }
        add(Ui.button(this, "保存模型设置", true) { safely { repository.save(read()); toast("已保存"); navigate("keyboard") } }, height = 54)
        val testStatus = Ui.text(this, "连接测试将发送“你好”给所填模型。", 13f, Ui.muted); add(testStatus)
        val test = Ui.button(this, "测试真实连接") {}
        test.setOnClickListener {
            val set = read().copy(demoMode = false)
            cancelWork(); val ticket = generation; test.isEnabled = false; testStatus.text = "正在测试 ${set.model.ifBlank { "模型" }}…"
            pending = workers.submit {
                val result = runCatching { AiClient().generate(set, ReplyRequest("你好")) }
                runOnUiThread {
                    if (ticket != generation || isDestroyed) return@runOnUiThread
                    test.isEnabled = true; testStatus.text = result.fold({ "连接成功，收到 ${it.size} 条有效候选。请点保存以应用设置。" }, { (it as? AiException)?.message ?: "连接失败，请检查地址、模型与网络。" })
                }
            }
        }
        add(test)
        val fieldsChanged = {
            cancelWork(); test.isEnabled = true; testStatus.text = "连接测试将发送“你好”给所填模型。"
            scheduleCatalog()
        }
        base.addTextChangedListener(SimpleTextWatcher {
            val nextOrigin = origin(it)
            if (nextOrigin != credentialOrigin) {
                credentialOrigin = nextOrigin
                if (key.text.isNotEmpty()) { key.setText(""); toast("服务地址已变更，请填写对应的 API Key。") }
            }
            fieldsChanged()
        })
        key.addTextChangedListener(SimpleTextWatcher { fieldsChanged() })
        model.addTextChangedListener(SimpleTextWatcher { cancelWork(); test.isEnabled = true; testStatus.text = "连接测试将发送“你好”给所填模型。" })
        scheduleCatalog()
    }

    private fun showModelPicker(models: List<AvailableModel>, current: String, selected: (AvailableModel) -> Unit) {
        if (models.isEmpty()) return
        val body = Ui.column(this).apply { setPadding(dp(20), dp(8), dp(20), 0) }
        val search = EditText(this).apply { hint = "搜索模型名称"; setSingleLine(true); id = R.id.model_search; isSaveEnabled = false }
        body.addView(search, LinearLayout.LayoutParams(-1, dp(52)))
        val list = ListView(this).apply { id = R.id.model_catalog_list }
        body.addView(list, LinearLayout.LayoutParams(-1, dp(340)))
        var filtered = models
        fun update(query: String) {
            filtered = models.filter { it.id.contains(query, true) || it.displayName.contains(query, true) }
            list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, filtered.map {
                val label = if (it.id == it.displayName) it.id else "${it.displayName}\n${it.id}"
                if (it.id == current) "✓ $label" else label
            })
        }
        update("")
        search.addTextChangedListener(SimpleTextWatcher { update(it.trim()) })
        val dialog = AlertDialog.Builder(this).setTitle("选择可用模型").setView(body).setNegativeButton("取消", null).create()
        list.setOnItemClickListener { _, _, position, _ -> filtered.getOrNull(position)?.let { selected(it); dialog.dismiss() } }
        catalogDialog?.dismiss(); catalogDialog = dialog
        dialog.setOnDismissListener { if (catalogDialog === dialog) catalogDialog = null }
        dialog.show()
    }

    private fun preferenceScreen() {
        title("表达偏好", "让回复更像你，也更适合当下的场景", true)
        val old = settings()
        val style = field("表达风格", old.style, limit = 200)
        val prompt = field("自定义 Prompt", old.customPrompt, true, limit = AiLimits.CUSTOM_PROMPT).apply { hint = "例如：短句，不使用夸张情话，尽量不加 emoji" }
        note("自定义要求会与所选人设、亲密度一起用于本次生成。")
        val levelLabel = Ui.text(this, "亲密度 ${old.intimacy}% · ${relationship(old.intimacy)}", 14f, Ui.accent); add(levelLabel)
        val level = SeekBar(this).apply { max = 100; progress = old.intimacy }
        level.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, progress: Int, user: Boolean) { levelLabel.text = "亲密度 $progress% · ${relationship(progress)}" }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        }); add(level)
        add(Ui.text(this, "键盘外观", 15f, bold = true)); var theme = KeyboardPreferences.theme(this)
        val themes = listOf("dark", "lavender", "light")
        choice(content, listOf("深灰蓝紫", "淡紫", "浅色"), themes.indexOf(theme)) { theme = themes[it] }
        val haptics = Switch(this).apply { text = "按键触感"; isChecked = KeyboardPreferences.haptics(this@MainActivity); minHeight = dp(48) }; add(haptics)
        add(Ui.button(this, "保存偏好", true) { safely {
            repository.save(settings().copy(style = style.text.toString().trim().ifBlank { "自然简洁" }, customPrompt = prompt.text.toString(), intimacy = level.progress))
            KeyboardPreferences.setTheme(this, theme); KeyboardPreferences.setHaptics(this, haptics.isChecked); toast("已保存，重新打开键盘即可应用"); navigate("keyboard")
        } })
    }
    private fun memoryScreen() {
        title("联系人记忆", "只记录你主动填写的关系与偏好", true)
        add(Ui.button(this, "+ 添加联系人记忆", true) { editMemory(null) })
        val entries = memories()
        if (entries.isEmpty()) note("还没有联系人记忆。生成时可选择是否引用；不会自动关联或读取通讯录。")
        entries.forEach { memory -> card { body ->
            add(Ui.text(this, memory.name, 18f, bold = true), body, 5); add(Ui.text(this, memory.notes.ifBlank { "暂无备注" }, 14f, Ui.muted), body)
            val actions = Ui.row(this)
            actions.addView(Ui.button(this, "编辑") { editMemory(memory) }, LinearLayout.LayoutParams(0, dp(48), 1f))
            actions.addView(Ui.button(this, "删除") {
                AlertDialog.Builder(this).setTitle("删除 ${memory.name} 的记忆？").setMessage("只删除本机保存的这条联系人记忆。")
                    .setPositiveButton("删除") { _, _ -> safely { repository.deleteMemory(memory.id); if (memoryId == memory.id) memoryId = null; navigate("memory") } }.setNegativeButton("取消", null).show()
            }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(8) })
            add(actions, body, 0)
        } }
        note("联系人记忆加密保存在本机。只有在本次生成中选择联系人时，才会发送该条备注。")
    }
    private fun editMemory(memory: ContactMemory?) {
        val body = Ui.column(this).apply { setPadding(dp(20), dp(10), dp(20), 0) }
        val name = field("联系人名称", memory?.name.orEmpty(), parent = body, limit = 80)
        val notes = field("关系、偏好和需要记住的信息", memory?.notes.orEmpty(), true, parent = body, limit = AiLimits.MEMORY)
        val dialog = AlertDialog.Builder(this).setTitle(if (memory == null) "添加联系人" else "编辑记忆").setView(body).setPositiveButton("保存", null).setNegativeButton("取消", null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { safely {
            val value = if (memory == null) ContactMemory(name = name.text.toString(), notes = notes.text.toString()) else memory.copy(name = name.text.toString(), notes = notes.text.toString())
            repository.upsertMemory(value); dialog.dismiss(); navigate("memory")
        } } }; dialog.show()
    }
    private fun sandboxScreen() {
        title("输入练习", "在这里试试中文输入和 AI 候选，不会向任何人发消息", true)
        card { body ->
            add(Ui.text(this, "TA：今天真的好累", 17f, bold = true), body)
            note("复制对方消息后打开键盘，默认进入“帮你回”并自动导入剪贴板；检查内容后点人设生成回复，也可切换到键盘输入。", body)
            val feedback = Ui.text(this, "试用发送只清空这里的草稿，不会联系任何人", 12f, Ui.muted).apply { id = R.id.sandbox_send_status }
            field("练习输入框", multiline = true, id = R.id.sandbox_editor, parent = body).apply {
                hint = "在这里输入，或自动填入候选回复"
                setOnEditorActionListener { _, action, _ ->
                    if (action == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) {
                        text.clear()
                        feedback.text = "已完成本地试用发送，没有向任何人发消息"
                        true
                    } else false
                }
            }
            add(feedback, body)
        }
        card { body ->
            val secure = field("密码输入练习", id = R.id.password_editor, parent = body)
            secure.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            note("密码输入框会停用 AI。这里的内容不会被保存。", body)
        }
        add(Ui.button(this, "切换输入法") { (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker() })
    }
}

internal class SimpleTextWatcher(private val changed: (String) -> Unit) : android.text.TextWatcher {
    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { changed(s?.toString().orEmpty()) }
    override fun afterTextChanged(s: android.text.Editable?) {}
}
