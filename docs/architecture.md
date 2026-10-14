# 架构

Android客户端直接请求用户配置的模型服务，不依赖项目后端。

| 模块 | 作用 |
|---|---|
| ime | 原生键盘、宿主编辑接口、会话/草稿保护 |
| ui | 设置、试用页、键盘内相册、主题与图标 |
| ai | 请求模板、四类协议、模型目录、HTTP传输 |
| data | AndroidKeyStore加密配置和手动记忆 |
| pinyin / cpp | AOSP离线拼音引擎与独立JNI桥接 |

SmartKeyImeService生成前捕获编辑器会话和草稿快照，收到结果后确认未变化才自动填入。切换候选只替换本次填入且未编辑的内容；润色安全选择原稿或选中区域。点击发送才调用宿主EditorAction，不保证每个宿主都支持发送。

AiClient后台执行，任务包括回复、润色、开场白、邮件、翻译和分析。PromptComposer区分待处理资料与系统要求；返回恰好三个非空、不重复的候选。分析路径只展示卡片，不提交宿主。

共享OkHttp连接池，不自动重试或跟随重定向。连接15秒、读取45秒、整次请求60秒；取消触发Call.cancel释放网络等待，generation/session拒绝迟到结果。专用快速参数仅添加到已验证DeepSeek V4服务。

ModelCatalogClient查询兼容models、Anthropic models、Gemini可生成模型和Ollama tags；最多500模型/5页。配置origin改变时清空旧密钥，迟到目录不能覆盖新配置。

设置与记忆使用AndroidKeyStore AES-GCM，备份排除凭证；普通输入和OCR内容不保存为历史。敏感InputType禁用AI与剪贴板导入。错误不输出服务器原始调试信息或密钥。

图库仅在主动进入时读取MediaStore，选图最多3张，按选择顺序本地OCR；用户主动分析或生成才提交识别文字。图库缩略图不上传。原生拼音引擎不启用持久化用户词典学习。

命名空间和旧输入法组件仅用于升级兼容；新实现、原生库及构建名称为SmartKey。第三方说明见[来源清单](../THIRD_PARTY.md)。
