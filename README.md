# 四六级 AI 学习助手

Android 客户端使用 Java、XML Views 和 Room。Spring Boot 服务提供学生账号和在线 AI 能力。阅读材料、生词和练习草稿按账号保存在手机上。

## 0.9.0 新增能力

- **独立查词**：`GET /api/v1/dictionary/suggest` 提供前缀联想（可按 CET-4 / CET-6 过滤），`POST /api/v1/dictionary/lookup` 查询词条并自动还原词形（如 `abandoned` → `abandon`），同时返回音标、词性、释义、用法说明和双语例句。查词记录与词典收藏都按账号保存在手机本地。
- **AI 助教流式对话**：`POST /api/v1/tutor/chat/stream` 基于 Spring AI（live 模式）与 Servlet `SseEmitter`，按 `meta` → 多条 `delta` → `done`（含 `followUps`）推送 `text/event-stream`。查词、句法分析、翻译批改和写作批改页均可携带上下文「追问 AI 助教」，也能从底部「AI 助教」进入自由问答。
- **Room v4 与多级返回栈**：数据库升级到 v4（新增 `dictionary_history`、`tutor_messages`，`favorites` 增加 `lemma` / `cet_level` / `source_type`），并提供 3→4 迁移。界面新增首页 / 查词 / AI 助教 / 生词本四栏底栏，使用 `NavigationStack` 管理 L1 Tab 与 L2/L3 页面，返回时保留生词本筛选、练习进度、翻译和写作草稿。

接口字段与错误码详见 [服务端 README](server/README.md)，构建与真机安装详见 [Android README](android/README.md)。

## 安装 0.8.0 并连接真机

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

## 真机验收

1. **离线回原文。** 在线登录后打开内置示例，查第二处 `decline` 并收藏。断开手机 Wi-Fi 和移动数据，同时拔掉 USB，确保 ADB 转发也中断。到系统的应用信息里点「强行停止」，再打开 App。进入生词本，完成一道填空并点「回原文」，确认高亮的是第二段的 `decline`。
2. **草稿恢复。** 分别在写作和汉译英里输入自选题目、中文原文和英文草稿。退出练习再进入确认内容仍在。再次强行停止并重开，确认两个模块都能独立继续写。换一个学生账号，确认看不到上一个账号的草稿和收藏。
3. **真机 OCR。** 连接后端后用手机拍一页纸质英文，从相册另导入一张图。检查校对与分段，确认保存后两篇都能重新打开。
4. **真实模型。** 按 [服务端 live 启动说明](server/README.md#33-live-模式调用真实模型) 配置模型，将后端切换至 live 模式。用刚拍的英文材料试查词和拆解长句，再用自选题目验证翻译和写作。写作重点核对引文是否来自提交的作文、换题是否仍在考同一个知识点，以及合理的不同答法能否通过。
