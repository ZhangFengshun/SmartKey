package dev.lovekey.open.ai

enum class ProviderKind(
    val displayName: String,
    val defaultBaseUrl: String,
    val defaultModel: String,
) {
    OPENAI_COMPATIBLE("OpenAI 兼容 / DeepSeek / Qwen / vLLM", "https://api.openai.com/v1", ""),
    ANTHROPIC("Anthropic Claude", "https://api.anthropic.com/v1", ""),
    GEMINI("Google Gemini", "https://generativelanguage.googleapis.com/v1beta", ""),
    OLLAMA("Ollama 本地模型", "http://127.0.0.1:11434", ""),
}

enum class ReplyTask(val displayName: String) {
    REPLY("帮我回复"), REWRITE("帮我润色"), OPENER("开场白"), EMAIL("邮件撰写"), TRANSLATE("翻译"), ANALYZE("读懂TA"),
}

data class Persona(val id: String, val name: String, val prompt: String)

object Personas {
    private val legacy = listOf(
        Persona("warm", "温柔陪伴", "语气温柔、真诚，先理解感受，再给予自然的回应。避免油腻和过度承诺。"),
        Persona("humor", "幽默有趣", "轻松幽默、自然有趣，可以适度俏皮，但不冒犯、不贬低，不强行玩梗。"),
        Persona("emotional", "高情商", "照顾双方感受，尊重边界，清晰表达立场，给出体贴且具体的沟通建议。"),
        Persona("professional", "职场专业", "表达简洁、专业、明确，突出事实、责任、下一步安排，不编造信息。"),
        Persona("sweet", "甜言蜜语", "真诚表达欣赏和关心，使用自然的亲近措辞。根据双方关系掌握分寸，不夸大承诺、不施压。"),
        Persona("caring", "暖心关怀", "关注对方具体的感受与需要，以平等、体贴的方式回应。提供可行支持，不说教、不替对方作决定。"),
        Persona("playful", "幽默有梗", "用轻巧的联想和俏皮表达缓和气氛。梗要贴合语境，避免攻击、讽刺敏感点和重复套路。"),
        Persona("curious", "话题延伸", "接住对方提到的具体细节，适度分享想法，再提出一个自然开放的问题，让交流顺畅延续。"),
        Persona("friend", "自然朋友", "像熟悉的朋友一样轻松真诚地说话，避免客服腔和刻意暧昧，按真实语境表达关心或观点。"),
        Persona("steady", "成熟稳重", "语气平和、有分寸，先厘清事实和感受，再清晰表达建议或边界。避免居高临下和情绪操控。"),
        Persona("academic", "学术沟通", "用准确、礼貌、清晰的语言沟通研究或学术问题，区分事实与推测，不编造文献、数据、引用或成果。"),
        Persona("direct", "简洁直说", "优先用短句表达最关键的信息或需求，明确但保持礼貌，避免套话和多余铺垫。"),
    )

    val all: List<Persona> = legacy + ReferenceKeyboardCatalog.replyPersonas.map {
        Persona(it.id, it.name, it.prompt)
    }

    fun find(id: String): Persona = all.firstOrNull { it.id == id } ?: all.first()
}

data class AiSettings(
    val provider: ProviderKind = ProviderKind.OPENAI_COMPATIBLE,
    val baseUrl: String = ProviderKind.OPENAI_COMPATIBLE.defaultBaseUrl,
    val model: String = "",
    val apiKey: String = "",
    val personaId: String = "warm",
    val customPrompt: String = "",
    val style: String = "自然简洁",
    val intimacy: Int = 50,
    val demoMode: Boolean = false,
) {
    // Do not let accidental diagnostic logging reveal the user's credential.
    override fun toString(): String = "AiSettings(provider=$provider, model=$model, apiKey=[redacted], demoMode=$demoMode)"
}

data class ReplyRequest(
    val text: String,
    val task: ReplyTask = ReplyTask.REPLY,
    val context: String = "",
    val contactMemory: String = "",
    val targetLanguage: String = "中文",
)

data class ReplyCandidate(val title: String, val text: String)

/** These messages are safe to display. Never attach a raw service response or credential. */
class AiException(message: String) : Exception(message)

object AiLimits {
    const val TEXT = 8_000
    const val CONTEXT = 8_000
    const val MEMORY = 4_000
    const val REQUEST_MEMORY = MEMORY + 128
    const val CUSTOM_PROMPT = 4_000
    const val CANDIDATE = 4_000
    const val RESPONSE_BYTES = 256 * 1024
    const val CATALOG_RESPONSE_BYTES = 2 * 1024 * 1024
}
