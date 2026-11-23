# 四六级 AI 学习助手 · iOS

> 接口字段见 [`docs/api-contract.md`](../docs/api-contract.md)，跨端验证脚本见 [`docs/verification-notes.md`](../docs/verification-notes.md)。

SwiftUI 客户端，最低支持 iOS 17。与 Android 端共用同一套 Spring Boot 服务与 demo 数据：支持注册登录、阅读点词查义与长难句拆解、独立查词与联想、AI 助教 SSE 流式问答、生词本与原句填空、汉译英提示与批改、写作批改与针对性复测。底部四栏（首页 / 查词 / AI 助教 / 生词本）配合多级返回栈，L2/L3 页面保留阅读选区、筛选条件与练习进度。阅读材料可通过相册或相机导入一页图片，本机用 Vision 识别后校对保存。服务根地址、账号凭证、学习数据与草稿均保存在本机。

## 构建与运行

需要 macOS、Xcode 27.2（含 iOS 27.2 模拟器运行时）。工程为 `CetHelper.xcodeproj`，scheme 为 `CetHelper`，产物包名为 `com.cethelper.ios`，版本 `0.9.0`。

以下命令从项目根目录执行。`SIM_ID` 取任意一台 iOS 27.2 iPhone 模拟器。

```sh
cd ios
SIM_ID=$(xcrun simctl list devices available -j | python3 -c '
import json,sys
d=json.load(sys.stdin)["devices"]
c=[x["udid"] for rt,v in d.items() if "iOS-27-2" in rt for x in v if x["name"].startswith("iPhone")]
print(c[0] if c else "")')
[ -n "$SIM_ID" ] || { echo "缺少 iOS 27.2 iPhone 模拟器运行时"; exit 1; }

xcodebuild -project CetHelper.xcodeproj -scheme CetHelper \
  -destination "platform=iOS Simulator,id=$SIM_ID" \
  -derivedDataPath build CODE_SIGNING_ALLOWED=NO build

xcrun simctl boot "$SIM_ID"
open -a Simulator
xcrun simctl install "$SIM_ID" build/Build/Products/Debug-iphonesimulator/CetHelper.app
xcrun simctl launch "$SIM_ID" com.cethelper.ios
```

调试构建允许访问本地 HTTP；正式发布需改为 HTTPS。

## 连接本地后端

按 [服务端 README](../server/README.md) 启动服务，默认端口 `8080`，demo 模式无需模型密钥。模拟器与 Mac 共享网络，App 内的服务端地址使用 `http://127.0.0.1:8080` 即可。登录页点「设置服务端地址」可以修改并测试连接；客户端会自行追加 `/api/v1`，保存时会去掉末尾 `/`。

```sh
cd server
./mvnw -B package
java -jar target/cet-server-0.1.0.jar \
  --server.address=127.0.0.1 --server.port=8080 --cet.explanation.mode=demo
```

## 0.9.0 功能

1. **阅读与导入。** 首页内置示例文章 `Two meanings of “decline”`。点按单词查释义，长按拖选同句词组后点系统菜单「查释义」；跨句选区会提示 `请在同一句中选择单词或词组。`。点词后可「分析整句」，按服务端返回顺序高亮片段。相册或相机导入英文页后进入校对页，可折叠原图对照、追加一页、修改标题与正文；保存后直接进入阅读页。
2. **生词本与原句填空。** 收藏按服务根地址、学生 ID、文章 ID 与 UTF-16 区间去重。生词本支持搜索、回原文滚动高亮、二次确认移除。原句填空按艾宾浩斯节奏出题，只挖空收藏时的选区；答错或查看答案计 0，答错后再答对不计独立答对。
3. **汉译英。** 可输入、粘贴、使用演示材料或从相册/相机导入中文页。关键词、短语、句子结构三类提示按需请求；提交后显示 15 分制预估分、逐项问题与「定位引文」快照，快照点「确定」关闭。修改草稿后旧的内联高亮会清除，并提示反馈对应上一次提交。自选材料在 demo 模式返回 422 时显示温和提示且不提供重试。
4. **写作。** 题目与作文可编辑，实时显示词数档位。提交后展示总体反馈、问题引用、原因和建议；练习挂在 `exercise.issueIndex` 对应的问题下，完成小练习后可「换题复测」，复测按同一知识点判定 `TARGET_USED`。历史批改支持回填与删除。
5. **独立查词。** 底部「查词」Tab 支持 250ms 防抖联想、全部 / CET-4 / CET-6 级别筛选、最近查词历史与清空；词条展示音标、词性、级别、释义、用法说明与双语例句，可朗读、加入生词本，并一键「追问 AI 助教」。
6. **AI 助教 SSE。** 底部「AI 助教」Tab 按自由 / 单词 / 句法 / 翻译 / 写作五种上下文挂载会话；`URLSession.bytes` 逐字节解析 `meta → delta → done(followUps)`，支持 50ms 节流流式渲染、主动停止、推荐追问与数据库会话恢复。阅读查词抽屉、长难句面板、汉译英批改、写作批改四处均可携上下文进入 L2 助教，返回后原页面状态 100% 保留。
7. **四栏导航与返回栈。** L1 根页显示首页 / 查词 / AI 助教 / 生词本四栏，进入 L2/L3 自动隐藏；`SessionScope` 统一维护 `activeTab` 与 `NavigationStack`，切 Tab 只清空返回栈，不销毁各页 ViewModel。
8. **离线与草稿。** 在线登录并成功校验 `/me` 后会缓存会话；断网冷启动按缓存账号进入本机内容，并在顶部显示 `离线模式 · 正在等待服务恢复`，恢复联网后自动复验且不离开当前页。汉译英原文/译文、写作题目/作文自动保存到本机，杀进程重启后仍可继续。服务端返回 401 时清除会话并提示 `会话已失效，请重新登录。`。
9. **外观与无障碍。** 浅色/深色两套色板跟随系统，主要按钮、输入框和列表项都带有稳定的 accessibility identifier，便于 XCUITest 与 VoiceOver 定位。

## 本机数据与隔离

- SQLite 数据库 `study.db` 为 WAL 模式，`user_version = 4`，保存导入文章、收藏、复习记录、练习记录、词典查词历史与助教会话消息；`favorites` 新增 `lemma` / `cet_level` / `source_type`，词典收藏以 `articleId = "__dict__"` 标记；v3 → v4 迁移无损保留旧生词。
- 文章、收藏、词典历史与助教消息均按 `serverUrl + studentId` 隔离；助教会话另按确定性的 `sessionKey` 分组，冷启动后可恢复同一上下文历史。
- 会话缓存（服务端地址、token、已验证学生）与草稿使用 `UserDefaults`，草稿键以 `studentId|serverUrl` 为前缀；退出登录会保留本机学习数据，但不会向其他账号展示。
- 字号档位、示例文章滚动位置同样按服务端与学生维度持久化。

## 测试

### 单元测试（57 项）

`CetHelperTests/` 使用 `@testable import CetHelper`，覆盖 U1–U14 核心单元测试零回归，并新增 v4 用例：v3 → v4 无损迁移与新旧库 `user_version`、词典收藏大小写不敏感查找/删除、词典历史与助教消息账号隔离、词典 JSON 解码与 `Favorite.fromDictionary`、`"__dict__"` 原句填空、SSE 解析（注释/多行 data/error/EOF/取消零回调）、5 种 `TutorContext` 摘要与确定性 `sessionKey`、生词本三级筛选与关键词检索。

```sh
cd ios
xcodebuild -project CetHelper.xcodeproj -scheme CetHelper \
  -destination "platform=iOS Simulator,id=$SIM_ID" \
  -derivedDataPath build CODE_SIGNING_ALLOWED=NO \
  -resultBundlePath build/Test.xcresult test
```

### 集成测试（真实 demo 服务，12 项）

`CetHelperTests/Integration/LiveServerTests.swift` 通过 `CET_E2E_BASE_URL` 读取服务地址；未注入时自动 `XCTSkip`。既有 9 项覆盖 health/demo、注册登录 `/me`/登出与旧 token 401、重复注册 409、`decline` 查词、整句分析 chunk 定位、三类提示、翻译批改、写作批改 → 小练习 → 换题复测、自选材料 422；新增 3 项覆盖词典联想过滤/limit/非法 level 400、`abandoned` 还原 lemma 与 422 `DEMO_UNSUPPORTED`、AI 助教 SSE 事件顺序与空 messages 400。

```sh
TEST_RUNNER_CET_E2E_BASE_URL=http://127.0.0.1:8080 \
xcodebuild -project CetHelper.xcodeproj -scheme CetHelper \
  -destination "platform=iOS Simulator,id=$SIM_ID" \
  -derivedDataPath build -resultBundlePath build/Test.xcresult \
  CODE_SIGNING_ALLOWED=NO test
```

### 模拟器 UI E2E（19 项，E1–E20）

`CetHelperUITests/CetHelperUITests.swift` 覆盖 E1–E20：既有 E1–E16 零回归，新增 E17 四栏导航与首页服务状态、E18 独立查词与生词本联动、E19 生词本三级筛选与 `"__dict__"` 填空跳转、E20 AI 助教 SSE 流式与阅读/句法/翻译/写作四处追问保态。E15 由深色全量复跑覆盖。启动参数统一为 `-server_url http://127.0.0.1:8080 -cet_reset_state YES`。截图以 `XCTAttachment` 随结果包保存。

```sh
# 相册 fixture（只需一次）
for photo in decline-examples river-passage translation-passage blank-page; do
  xcrun simctl addmedia "$SIM_ID" ../android/qa/$photo.jpg
done

# 只跑 UI 套件（浅色）
xcodebuild -project CetHelper.xcodeproj -scheme CetHelper \
  -destination "platform=iOS Simulator,id=$SIM_ID" \
  -derivedDataPath build -resultBundlePath build/UI-light.xcresult \
  CODE_SIGNING_ALLOWED=NO -only-testing:CetHelperUITests test

# 深色复跑
xcrun simctl ui "$SIM_ID" appearance dark
TEST_RUNNER_E2E_APPEARANCE=dark xcodebuild ... -only-testing:CetHelperUITests test
```

E12（离线）与 E13（401 全局登出）需要 `ios/Tools/e2e_proxy.py`：它把 `http://127.0.0.1:8090` 转发到 demo 服务，并在 `8091` 暴露 `/pause`、`/resume`、`/revoke` 控制接口。未启动代理时这两条用例 `XCTSkip`。

```sh
python3 ios/Tools/e2e_proxy.py
```

## 2026-11-22 实测记录

环境：Xcode 27.2（`27B52`）、iPhone 17 模拟器、iOS 27.2（`24B8117`），重新打包的 demo 服务 `127.0.0.1:8080`（v0.2.0，含词典与 SSE 接口）。

| 项目 | 结果 |
| --- | --- |
| 单元测试 | 57 / 57 通过 |
| 真实服务集成测试 | 12 / 12 通过 |
| UI E2E（浅色） | 19 / 19 通过 |
| UI E2E（深色） | 19 / 19 通过 |
| 全量 `xcodebuild test` | `** TEST SUCCEEDED **`，69 tests，0 失败 0 跳过 |

覆盖亮点：

| 场景 | 结果 |
| --- | --- |
| 登录与导航 | 登录页「登录 / 注册新账号」双按钮；注册成功提示后登录；L1 四栏底栏仅在根页显示，进入 L2 阅读/查词/助教后隐藏，返回恢复；各页自定义顶栏统一 `nav.back`。 |
| 独立查词 | 查词 Tab 输入 `abandon` 联想命中；详情显示音标、词性、CET-6、释义与双语例句；收藏后按钮变「已在生词本」，并同步出现在生词本；`dict.askTutor` 进入单词上下文 L2 后返回详情保留。 |
| AI 助教 | 阅读查词抽屉、长难句、汉译英批改、写作批改四处追问入口分别进入单词/句法/翻译/写作上下文；快捷提问触发 SSE 流式，收到 `done` 后展示推荐追问；逐级返回后阅读面板、分析结果、译文草稿与评分、写作评分全部保留。 |
| 生词本与填空 | CET-6 + 词典筛选与搜索词在 L2 往返后保留；词典收藏的跳转文案为「词典详情」；填空答对后从反馈页以「词典详情」压栈 L3 查词，返回后填空进度不丢。 |
| 查词与收藏 | 点中 `decline` 返回释义、meta 与「演示释义」；收藏后首页生词数为 1；杀进程重启后仍可回原文。 |
| 跨句与乱序 | 长按拖过句号后提示同句选择；快速连点两个词只显示最后一次结果。 |
| 原句填空 | 首页经生词本进入；首字母提示默认收起，点击 `cloze.hintButton` 展开；空提交、答错结果卡、总结、仅重练错题答对全链路，答错显示「独立答对 0 / 1 题」，重练答对后为「独立答对 1 / 1 题」。 |
| 汉译英 | 三类提示格式正确（含「句子结构」），演示译文得到 `9 / 15`；定位引文弹出快照后点「确定」关闭，修改草稿后提示反馈对应上一次提交。 |
| 写作 | 演示作文 `10 / 15`；小练习判定「正确」；换题复测判定「已正确运用」；历史批改可打开。 |
| 草稿与离线 | 回到桌面、杀进程、重启后两类草稿均恢复；代理断网时显示离线横幅且草稿保留，恢复后横幅自动消失。 |
| 401 登出 | 用 App 的实际 token 调用 `/auth/logout` 后触发业务请求，回到登录页并提示会话失效。 |
| OCR 导入 | 相册英文页识别、折叠原图对照、追加一页、保存进阅读页；中文页从「汉译英 → 从相册导入」进入作答页；模拟器拍照入口禁用。 |

## 已知限制

- 模拟器没有相机，拍照导入/拍照追加按钮为禁用态；真机相机、系统相册权限弹窗与真实网络环境仍建议在设备上复验。
- UI E2E 依赖固定尺寸与文案（iPhone 17，402×874 pt）。更换机型或改文案时需同步调整点击坐标和断言。
- demo 模式只覆盖内置材料；live 模型质量、超时与额度错误需要配置真实密钥后另行验收。
- E12/E13 依赖本机控制代理，不能直接在无代理的 CI 中运行（会自动跳过）。
