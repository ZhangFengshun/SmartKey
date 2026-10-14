# 技术资料

项目的原生键盘实现参考[Android IME指南](https://developer.android.com/develop/ui/views/touch-and-input/creating-input-method)，编辑接口不能读取完整聊天记录。

中文全拼使用AOSP PinyinIME解码器和词典，保留版权与NOTICE。准确版本、校验和与修改范围见[原生来源说明](../android/app/src/main/cpp/aosp/README.md)。其它键盘项目未作为本项目直接代码来源。

图片文字识别使用bundled中文ML Kit模型，流程见[官方说明](https://developers.google.com/ml-kit/vision/text-recognition/v2/android)。网络请求使用OkHttp；其它依赖及声明见[第三方清单](../THIRD_PARTY.md)。

公开仓库不包含外部产品截图、私人聊天观察或测试设备信息。功能目录为本项目独立代码与提示词，不包含其它产品的云端提示词或账户服务。
