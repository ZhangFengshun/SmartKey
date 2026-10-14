package dev.lovekey.open.ai

/** Explicit offline examples, not model-generated and never used as a network-error fallback. */
internal object DemoReplies {
    fun generate(request: ReplyRequest): List<ReplyCandidate> = when (request.task) {
        ReplyTask.ANALYZE -> listOf(
            ReplyCandidate("关系", "演示模板：仅凭当前文字不能确定双方身份或关系，需要结合明确提供的背景和持续互动判断。"),
            ReplyCandidate("情绪", "演示模板：这里将结合聊天中的具体措辞说明可能的情绪；当前模板没有进行真实模型分析。"),
            ReplyCandidate("沟通建议", "演示模板：先确认对方表达的意思，再回应具体感受或需要；避免臆测，尊重双方边界。"),
        )
        ReplyTask.REPLY -> listOf(
            ReplyCandidate("演示 · 温柔关心", "听起来今天不太轻松，要不要和我说说？我在听。"),
            ReplyCandidate("演示 · 轻松幽默", "给今天按个暂停键，先好好休息一下，剩下的明天再一起搞定。"),
            ReplyCandidate("演示 · 真诚陪伴", "辛苦啦。有什么我能帮上忙的，或者只想聊一会儿，都可以告诉我。"),
        )
        ReplyTask.REWRITE -> listOf(
            ReplyCandidate("演示 · 保留原文", request.text.take(1_000)),
            ReplyCandidate("演示 · 简洁表达", "想和你确认一下：${request.text.take(900)}"),
            ReplyCandidate("演示 · 温和表达", "方便的话，我想和你聊聊：${request.text.take(900)}"),
        )
        ReplyTask.OPENER -> listOf(
            ReplyCandidate("演示 · 自然开场", "今天过得怎么样？突然想和你聊聊最近的生活。"),
            ReplyCandidate("演示 · 轻松话题", "最近有没有发现什么让你开心的小事？分享一个给我吧。"),
            ReplyCandidate("演示 · 温柔问候", "刚刚想到你，来问问你今天还好吗。"),
        )
        ReplyTask.EMAIL -> listOf(
            ReplyCandidate("演示 · 正式邮件", "您好，关于这次沟通的事项，我想进一步确认安排。请您在方便时回复，谢谢。"),
            ReplyCandidate("演示 · 简洁邮件", "您好，想和您确认下一步的时间与安排。如有需要补充的信息，请告诉我。谢谢！"),
            ReplyCandidate("演示 · 友好邮件", "您好！感谢您的时间。期待与您进一步沟通，我们可以一起确认适合的下一步安排。"),
        )
        ReplyTask.TRANSLATE -> listOf(
            ReplyCandidate("演示 · 翻译说明", "演示模式不进行真实翻译。请配置模型后，将输入内容翻译为${request.targetLanguage}。"),
            ReplyCandidate("演示 · 正式译文", "这里将显示模型生成的正式译文。目标语言：${request.targetLanguage}。"),
            ReplyCandidate("演示 · 自然译文", "这里将显示模型生成的自然译文。原文：${request.text.take(900)}"),
        )
    }
}
