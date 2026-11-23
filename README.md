# 四六级 AI 学习助手

Android 客户端使用 Java、XML Views 和 Room；iOS 客户端使用 SwiftUI、Vision 与 SQLite，最低支持 iOS 17。Spring Boot 服务提供学生账号和在线 AI 能力，两端共用同一套接口、demo 数据和错误语义。阅读材料、生词和练习草稿按账号保存在手机上。

- Android：安装、构建与真机验收见下文，或直接打开 [Android README](android/README.md)。
- iOS：构建、模拟器验收与测试命令见 [iOS README](ios/README.md)。

## Android：安装 0.8.0 并连接真机

需要 JDK 21、Android SDK（Platform 35 和 Build Tools 35.0.0），以及已开启 USB 调试的 Android 手机。现成的 [0.8.0 Debug APK](android/app/build/outputs/apk/debug/app-debug.apk) 可直接安装。构建步骤见 [Android README](android/README.md)。

先从项目根目录启动本机 demo 后端。保持终端运行。首次构建需要拉取 Maven 依赖，已有构建产物时可跳过打包命令。

```sh
cd server
./mvnw -B package
java -jar target/cet-server-0.1.0.jar --server.address=127.0.0.1 --server.port=8080 --cet.explanation.mode=demo
```

另开终端并回到项目根目录。把下列命令中的 `DEVICE_SERIAL` 替换成第一条命令列出的真机序列号。若已有同签名的旧版，`install -r` 会覆盖安装并保留数据。

```sh
adb devices -l
adb -s DEVICE_SERIAL install -r android/app/build/outputs/apk/debug/app-debug.apk
adb -s DEVICE_SERIAL reverse tcp:8080 tcp:8080
```

在 App 登录页的「设置服务端地址」填入 `http://127.0.0.1:8080`，然后注册或登录。如果要在真机上保留旧版的本机数据，请沿用旧版的服务端地址、数据库和学生账号。覆盖安装之后先在线打开一次，让 0.8.0 验证账号并保存离线所需的信息。重新插拔 USB 后可重跑 `adb reverse`。

## Android：真机验收

1. **离线回原文。** 在线登录后打开内置示例，查第二处 `decline` 并收藏。断开手机 Wi-Fi 和移动数据，同时拔掉 USB，确保 ADB 转发也中断。到系统的应用信息里点「强行停止」，再打开 App。进入生词本，完成一道填空并点「回原文」，确认高亮的是第二段的 `decline`。
2. **草稿恢复。** 分别在写作和汉译英里输入自选题目、中文原文和英文草稿。退出练习再进入确认内容仍在。再次强行停止并重开，确认两个模块都能独立继续写。换一个学生账号，确认看不到上一个账号的草稿和收藏。
3. **真机 OCR。** 连接后端后用手机拍一页纸质英文，从相册另导入一张图。检查校对与分段，确认保存后两篇都能重新打开。
4. **真实模型。** 按 [服务端 live 启动说明](server/README.md#33-live-模式调用真实模型) 配置模型，将后端切换至 live 模式。用刚拍的英文材料试查词和拆解长句，再用自选题目验证翻译和写作。写作重点核对引文是否来自提交的作文、换题是否仍在考同一个知识点，以及合理的不同答法能否通过。

## iOS：0.9.0 构建与模拟器验收

iOS 端与 Android 对齐同一套能力：注册登录、阅读点词与长难句、独立查词与联想、AI 助教 SSE 流式问答、生词本与原句填空、汉译英、写作批改与复测，以及离线续用和草稿恢复。底部四栏（首页 / 查词 / AI 助教 / 生词本）配合多级返回栈，L2/L3 页面保留阅读选区、筛选条件与练习进度。界面用 SwiftUI 实现，图片识别用系统 Vision，数据用 SQLite（v4），服务端地址与 Android 共用 `http://127.0.0.1:8080`。

```sh
cd ios
xcodebuild -project CetHelper.xcodeproj -scheme CetHelper \
  -destination "platform=iOS Simulator,name=iPhone 17" \
  -derivedDataPath build CODE_SIGNING_ALLOWED=NO build
```

在 Xcode 27.2 的 iPhone 17 / iOS 27.2 模拟器上已完成全量验收：离线单元测试 57 项、真实 demo 服务集成测试 12 项、XCUITest 端到端 19 项（E1–E20，浅色与深色各一遍），全部通过、0 失败 0 跳过。E12 离线与 E13 401 登出由 `ios/Tools/e2e_proxy.py` 控制代理配合验证。详细命令、逐项结果、截图与已知限制见 [iOS README](ios/README.md)。
