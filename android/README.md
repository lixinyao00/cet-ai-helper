# 四六级 AI 学习助手 · Android

基于 **Java 17 + 原生 XML Views + Room v4 + Google ML Kit** 的 Android 客户端（`minSdk 26` / `targetSdk 35`，包名 `com.cethelper.android`），与服务端和 iOS 端共用同一套 `/api/v1` 契约与错误语义。

> 接口字段见 [`docs/api-contract.md`](../docs/api-contract.md)，历史阶段实测与跨进程回归记录见 [`docs/verification-notes.md`](../docs/verification-notes.md)。

## 1. 核心能力与本地架构

- **四栏主框架与多级返回栈**：底部「首页 / 查词 / AI 助教 / 生词本」作为 L1 根页面，通过 [`NavigationStack`](app/src/main/java/com/cethelper/android/NavigationStack.java) 管理 L2/L3 压栈，跨页面返回保留阅读选区、生词筛选条件与练习进度。
- **端侧 OCR 与分段导入**：[`PageOcr`](app/src/main/java/com/cethelper/android/PageOcr.java) 使用随包集成的 ML Kit 拉丁/中文模型提取文本，经 `/api/v1/articles/organize` 自动清理分段后入库。
- **Room v4 本地多账号隔离**：[`StudyDatabase`](app/src/main/java/com/cethelper/android/StudyDatabase.java) 包含 `favorites`、`articles`、`practice_records`、`dictionary_history`、`tutor_messages` 五张表（含 `1→2`、`2→3`、`3→4` 无损迁移），全部按 `(serverUrl, studentId)` 隔离。
- **离线续用与草稿恢复**：[`SessionStore`](app/src/main/java/com/cethelper/android/SessionStore.java) 与 [`DraftStore`](app/src/main/java/com/cethelper/android/DraftStore.java) 持久化当前服务端凭证指纹与翻译/写作草稿；断网冷启动可进入离线阅读与生词填空，恢复联网后通过 `/me` 静默复验。

## 2. 构建与运行

需要 **JDK 17 或 21**、**Android SDK Platform 35** 与 **Build Tools 35.0.0**（Gradle Wrapper 8.9）。

```sh
cd android
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

安装到已连接的设备或模拟器：

```sh
"$ANDROID_HOME/platform-tools/adb" install -r app/build/outputs/apk/debug/app-debug.apk
"$ANDROID_HOME/platform-tools/adb" shell am start -n com.cethelper.android/.MainActivity
```

## 3. 连接本地后端

| 运行环境 | 服务端地址设置 | 说明 |
| --- | --- | --- |
| **Android 模拟器** | `http://10.0.2.2:8080`（默认） | `10.0.2.2` 自动映射到宿主机环回地址 |
| **USB 真机** | `http://127.0.0.1:8080` | 需执行 `adb -s <序列号> reverse tcp:8080 tcp:8080` |
| **局域网真机** | `http://<电脑局域网IP>:8080` | 后端需监听 `0.0.0.0` 并放行防火墙 8080 端口 |

## 4. 测试

- **JVM 单元测试**：`./gradlew :app:testDebugUnitTest`（覆盖原句填空调度、词典收藏与 SRS、导航栈、OCR 版面合并、SSE 解析等）。
- **Instrumentation 端到端测试**：`./gradlew :app:connectedDebugAndroidTest`（含 `StudyDatabaseMigrationTest` 等 Room 迁移测试）。
- **跨进程回归脚本**：[`qa/draft_process_qa.py`](qa/draft_process_qa.py) 在隔离模拟器上覆盖 `am force-stop` 进程边界验证草稿恢复、多账号隔离、断网重连与 401 登出。
