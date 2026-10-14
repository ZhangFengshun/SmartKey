# 开发环境

Gradle根目录为项目根目录，Android模块为android/app。

| 组件 | 版本 |
|---|---|
| JDK | 17以上，建议21 |
| Gradle | 8.11.1 |
| AGP | 8.9.2 |
| Kotlin | 2.1.20 |
| SDK / Build Tools | 35 / 35.0.0 |
| NDK | 27.0.12077973 |
| CMake | 3.22.1 |

```powershell
powershell -ExecutionPolicy Bypass -File tools/setup.ps1
powershell -ExecutionPolicy Bypass -File tools/build.ps1
```

setup优先复用JAVA_HOME、ANDROID_HOME或ANDROID_SDK_ROOT，缺失工具安装到.toolchain并验证下载校验和，生成仅供本机使用的local.properties。环境脚本将Gradle缓存和临时目录放在.toolchain，避免某些Windows短路径兼容问题。

```powershell
powershell -ExecutionPolicy Bypass -File tools/device.ps1 devices -l
powershell -ExecutionPolicy Bypass -File tools/device.ps1 install -r android/app/build/outputs/apk/debug/app-debug.apk
```

ADB依次从SMARTKEY_ADB、项目SDK、ANDROID_HOME或PATH查找。安装不会自动启用新输入法，请使用应用引导在系统中启用并选择。debug构建采用开发签名，发布签名材料自行配置且不提交仓库。

本机SDK路径、缓存、日志与凭证必须留在忽略目录内。第三方原生来源见../android/app/src/main/cpp/aosp/README.md。
