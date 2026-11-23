# 联调脚本与里程碑验收记录

本文件归档服务端、Android 与 iOS 的自动化测试脚本入口及各版本验收实测记录。

## 自动化测试与 QA 脚本索引

| 脚本 / 工具 | 位置 | 用途 |
| --- | --- | --- |
| Live 契约冒烟脚本 | [`server/scripts/live_smoke.py`](../server/scripts/live_smoke.py) | 使用本地模拟 OpenAI 上游（`--stand-in`）或真实模型校验阅读、翻译、写作契约 |
| H2 → MySQL 迁移工具 | [`server/tools/MigrateH2ToMysql.java`](../server/tools/MigrateH2ToMysql.java) | 停服窗口内只读校验并迁移 H2 数据到 MySQL（支持 `--dry-run`） |
| Android 跨进程回归脚本 | [`android/qa/draft_process_qa.py`](../android/qa/draft_process_qa.py) | 覆盖 `am force-stop` 边界验证草稿恢复、账号隔离、断网重连与 401 登出 |
| Android OCR 测试素材 | [`android/qa/`](../android/qa) | `decline-examples.jpg`、`river-passage.jpg`、`translation-passage.jpg`、`blank-page.jpg`、`camera-page.jpg` |
| iOS E2E 断网/401 代理 | [`ios/Tools/e2e_proxy.py`](../ios/Tools/e2e_proxy.py) | 在 XCUITest 中按需制造断网（`/pause`）与凭证撤销（`/revoke`） |

---

## 服务端验证记录

## 12. 验证记录

实测环境：macOS 26.6.2（arm64），Temurin **21.0.13.1**（Adoptium；本轮复验使用该 JDK，Corretto 21 同样适用），Maven **3.9.16**（经仓库内 `./mvnw` 引导），Spring Boot 3.5.16。联调时服务监听 **18080**（避免与本地 8080 冲突），数据目录指向临时文件库；默认配置下 `server.port=8080`、数据在 `server/data/`。下表带「（本轮）」标记的行是本轮（整句分析）新增的实测项。

| 项目 | 命令 / 操作 | 实测结果 | 结论 |
|---|---|---|---|
| 构建 | `./mvnw -B clean test` | `BUILD SUCCESS`；`./mvnw -B -DskipTests package` 生成 `target/cet-server-0.1.0.jar`（59,576,554 字节） | 通过 |
| Maven Wrapper | `./mvnw -v` / `./mvnw -DskipTests compile` | 成功引导 Maven 3.9.16 并完成编译（本机出口访问 Maven Central 被拒，改用第 3.5 节镜像验证；干净网络下即为默认行为） | 通过 |
| 单元 / 集成测试 | `./mvnw -B clean test` | `Tests run: 139, Failures: 0, Errors: 0, Skipped: 0`（汉译英提示 demo 11、汉译英批改 demo 9、汉译英 live 19、整句分析 demo 11、整句分析 live 12、片段定位规则 5、ExplainDemo 11、ExplainLive 6、注册登录 22、身份与退出 12、凭证过期 2、唯一约束与脱敏 3、重启持久化 1、解释文案 4、输入规范化 7、严格 JSON 类型 4） | 通过 |
| 第 7 节脚本实跑 | 服务起在 18084（全新文件库），`BASE_URL=http://127.0.0.1:18084 bash smoke.sh` | 26 次请求（两次登录只取 token）的状态码全部与注释一致：注册 `201/201`、`/me` `200`、释义三例 `200`、整句分析两例 `200`、汉译英提示 `200`、两次批改 `200/200`、错误 `400/422/401/409/401/422/400/401/400/422`、退出 `204`、旧 token `401`、`stu_b` 仍 `200` | 通过 |
| demo 三例 HTTP | 真实服务 + curl，`stu_a` 的 Bearer 凭证 | 三条均 `200`；`mode=demo`；`meaning` 依次为 下降 / 婉拒 / 较低的需求；同一 `decline` 两语境释义不同（下降 vs 婉拒）；词组 `lower demand` 成功 | 通过 |
| 整句分析长句 HTTP（本轮） | 真实 demo 服务（18080）+ curl：`{"sentence":"While many students who are preparing for the CET-6 exam believe that memorizing word lists is enough, experienced teachers insist that reading widely is what really builds vocabulary."}` | `200`；`mode=demo`；`translation` 为「尽管许多正在备考六级的学生认为背单词表就够了，有经验的老师却坚持认为，广泛阅读才是真正积累词汇的方式。」；`structure` 指出主句 `experienced teachers insist`；`chunks` 6 条，顺序为 `While many students` → `who are preparing for the CET-6 exam` → `believe` → `that memorizing word lists is enough` → `experienced teachers insist` → `that reading widely is what really builds vocabulary` | 通过 |
| 整句分析内置两句（本轮） | 真实 demo 服务 + curl：文章两句原句 | 均 `200`；`mode=demo`；第一句 4 条片段（`The report` / `attributed` / `the decline` / `to lower demand`）、第二句 3 条片段（`Some students` / `decline` / `the invitation`） | 通过 |
| 整句分析片段位置（本轮） | 用 `indexOf(text, lastEnd)` 还原长句 6 个片段在原句中的区间 | 区间为 `0..19`、`20..56`、`57..64`、`65..101`、`103..130`、`131..183`（句长 184），严格递增、互不重叠，仅逗号与空格（下标 102）留白 | 通过 |
| 整句分析异常（本轮） | 真实 demo 服务 + curl：未收录句子 / 空白原句 / `sentence` 传数字 / 2001 字符 / 匿名 / 退出后旧 token | 依次 `422 DEMO_UNSUPPORTED`、`400 INVALID_INPUT`「请输入要分析的英文原句。」、`400 INVALID_INPUT`「请求字段类型不正确：字符串字段请使用 JSON 字符串。」、`400 INVALID_INPUT`「原句过长：最多 2000 个字符，请精简后再试。」、`401 UNAUTHORIZED`、退出后 `401 UNAUTHORIZED`（退出返回 `204`） | 通过 |
| 整句分析 live 假上游（本轮） | `SentenceAnalysisLiveApiTest`（本地 `HttpServer` 替身，12 例） | 正常拆解 `200`/`mode=live`；双 `that` 句按上游顺序返回 6 段且两段说明各自保留；片段顺序写反 → 服务端重排后仍 `200`；片段不在原句 / 片段缺 `explanation` / `chunks` 为空 → `502 MODEL_ERROR`；上游 500 → `502`；content 非 JSON → `502`；慢于 400ms 读超时 → `504 MODEL_TIMEOUT`；错误体不含替身诱饵密钥与堆栈；匿名与非法输入不触发上游调用 | 通过 |
| 双 `that` 修复（本轮） | 真实 live 服务（18082，`CET_MODEL_BASE_URL` 指向本地 Python 模拟上游）+ curl：`{"sentence":"They said that practice matters and that patience helps."}` | `200`；`mode=live`；6 段顺序为 `They said` → `that` → `practice matters` → `and` → `that` → `patience helps`；两个 `that` 的 `role` 分别为「第一个宾语从句的引导词」「第二个宾语从句的引导词」，`explanation` 分别为「练习很重要」「耐心有帮助」；对照句 `They said that practice matters and patience helps.`（只有一个 `that`）→ `502 MODEL_ERROR`；服务日志不含模拟密钥与异常堆栈 | 通过 |
| 整句分析真实模型 | 需 `CET_MODEL_BASE_URL` / `CET_MODEL_NAME` / `CET_MODEL_API_KEY` | 本环境无真实模型凭据，**未验证**；live 路径仅用本地假上游覆盖契约与容错 | 待验 |
| 汉译英结构提示（本轮） | 真实 demo 服务（18083）+ curl：内置材料 + `hintType=structure` | `200`；`mode=demo`；`structure.mainClause` 点出两句主干；`clauses` 2 条（时间状语、修饰年轻人的定语从句）、`connectives` 2 处（因此、那些），片段逐字出自原文 | 通过 |
| 汉译英草稿批改（本轮） | 真实 demo 服务 + curl：草稿译文 | `200`；`issues` 3 条，类型依次 `OMISSION`/`OMISSION`/`UNNATURAL`；引用为「对高校毕业生」「越来越」「have good team cooperation」，逐字可查 | 通过 |
| 汉译英补全后批改（本轮） | 真实 demo 服务 + curl：补全后的译文 | `200`；`issues` 为空数组；反馈为肯定与「上一版被标出的漏译与生硬表达都不再出现」 | 通过 |
| 两次批改的实际差异（本轮） | 用脚本对比 4.7 与 4.8 的响应的 `issues` | 草稿 3 条 → 补全后 0 条；草稿两条漏译引用在第二次批改的响应 `issues` 中不再出现；两次 `overallFeedback` 不同；准确译文与「另一种合理译法」均 `200` + 空 `issues`，且两者反馈文字不同 | 通过 |
| 汉译英 live 假上游（本轮） | `TranslationLiveApiTest`（本地 `HttpServer` 替身，15 例） | 三类提示成功 `200`/`mode=live`；所选类型字段缺失、中文片段不在原文、空结果、非 JSON → `502`；批改空问题列表与带问题列表透传，漏译引用不在原文 / 表达不自然引用不在学生译文 → `502`；上游 500 → `502`；读超时 → `504`；匿名与非法输入不触发上游调用 | 通过 |
| `expressions` 元素类型（本轮修复 1） | 真实 live 服务（18085，指向本地模拟上游）+ curl：`expressions:[null]`、`expressions:[123]` | 两类提示均 `502 MODEL_ERROR`（修复前为 `500 INTERNAL_ERROR`）；`expressions` 为合法字符串时 keyword / phrase 均 `200`；接口回归 4 例覆盖关键字与短语 | 通过 |
| 乱序引用定位（本轮修复 2） | 真实 live 服务 + curl：`sourceText` 为 `今天学生读书，明天老师备课。`，上游按「明天」「今天」返回两条 `OMISSION` | `200`；`issues` 顺序保留「明天」→「今天」；按 README 约定逐条独立 `indexOf` 得 7 与 0，两条都能定位；旧写法（沿用上一条结束位置）定位为 `-1`；同一引文出现 3 次时 `200` 且 `indexOf != lastIndexOf`，由客户端标记多处 | 通过 |
| demo 草稿重做（本轮修复 3） | 真实 demo 服务（18086）+ curl：新草稿、补全并改顺的译文、准确译文、另一种合理译法 | 草稿 `200`：3 条问题（`OMISSION`「对高校毕业生」、`OMISSION`「越来越」、`UNNATURAL` `have good team cooperation`）；补全后 `200` + 空 `issues`；准确译文与另一种译法均 `200` + 空 `issues` | 通过 |
| 汉译英真实模型 | 需 `CET_MODEL_BASE_URL` / `CET_MODEL_NAME` / `CET_MODEL_API_KEY` | 本环境无真实模型凭据，**未验证**；live 路径仅用本地假上游覆盖契约与容错 | 待验 |
| 身份隔离 | 注册两名学生后各调 `/me` | `201/201` 注册；`/me` 分别返回 `{"id":1,"username":"stu_a"}` 与 `{"id":2,"username":"stu_b"}` | 通过 |
| 重启后账号仍可登录 | 停服务后同一 H2 文件重启，再 `login` | `200`，`/me` 返回同一 `id`；重启前未撤销的凭证仍可用 | 通过 |
| 退出后旧凭证 401 | `logout` 后用旧 token 调 `/me` 与 `/explanations` | 退出 `204`（响应体 0 字节）；旧 token 两个接口均 `401`；二次退出 `401`；`stu_b` 的凭证仍 `200` | 通过 |
| 重复注册 409 | 同名注册两次 | 首次 `201`，再次 `409` 且 `{"code":"USERNAME_TAKEN",...}`；并发注册（2 线程）与串行结果一致 | 通过 |
| 错误密码 401 | 口令改错 / 账号不存在 | 均 `401`，错误体逐字相同 `{"code":"INVALID_CREDENTIALS","message":"用户名或密码不正确。"}` | 通过 |
| 匿名 / 无效凭证 401 | 不带 Authorization、伪造 token、`Basic` 方案、超长口令登录 | `401`；超长（100 字符）口令按凭据错误处理，不会 500 | 通过 |
| 选词不在原句 400 | `{"selection":"decline","sentence":"The report attributed the increase to higher demand."}` | `400`，`{"code":"INVALID_INPUT","message":"选词必须是原句中的连续原文。"}` | 通过 |
| 未收录 422 | demo 下查词表外生词（如 `quizzacious`） | `422`，`{"code":"DEMO_UNSUPPORTED",...}`；注：0.2.0 起 `invitation` 等常用词已由内置词表兜底返回 `200`（`contextual=false`） | 通过 |
| live 假上游 502 / 504 | `ExplainLiveApiTest`（本地 `HttpServer` 替身） | 上游 500 → `502 MODEL_ERROR`；content 非 JSON → `502`；响应慢于 400ms 读超时 → `504 MODEL_TIMEOUT`；错误体不含替身诱饵密钥 `sk-UPSTREAM-...`、上游正文与堆栈 | 通过 |
| live 缺配置的启动校验 | `CET_EXPLANATION_MODE=live` 且不设三个 `CET_MODEL_*` | 进程退出码 1，日志给出中文提示「live 模式缺少配置：请设置 CET_MODEL_BASE_URL、CET_MODEL_NAME 与 CET_MODEL_API_KEY……」 | 通过 |
| 日志脱敏 | 扫描两次运行日志 | 未出现明文口令、完整 `accessToken`、`sk-` 形态密钥；错误响应不含堆栈 | 通过 |
| 严格 JSON 类型（修复 2） | JAR + curl：注册/登录分别提交数字、小数、布尔值 | `{"password":12345678}`、`{"username":1001,...}`、`{"password":true}` 均 `400` `INVALID_INPUT`（提示「请求字段类型不正确：字符串字段请使用 JSON 字符串。」）；被拒的注册未建号，用字符串 `12345678` 登录得 `401`；字符串口令登录仍 `200` | 通过 |
| 首尾空白规范化（修复 3） | JAR + curl：选词/原句带空格、制表符、全角空格、换行 | 三条示例均 `200` 且释义正确；句内双空格不被折叠（`lower  demand` → `400`）；全空白选词 `400`；`selection` 传数字 `400`；live 用例额外断言上游收到的是去空白后的文本 | 通过 |
| 演示释义文案（修复 1） | JAR + curl + 单测 | `decline`+报告句 → 「这里作名词，指报告提到的下降；报告将这一下降归因于需求走低」；`lower demand` → 「需求较低；在这句话里，它是报告给出的下降原因」；`meaning` 与 `mode` 未变 | 通过 |
| 默认安全用户已关闭（修复 4） | 启动日志检查 | 日志中无 `Using generated security password`，也无任何默认用户/密码行；Bearer 登录、`/me`、退出 `204`、退出后 `401` 全部正常 | 通过 |
| 真实模型（G 项） | 需 `CET_MODEL_BASE_URL` / `CET_MODEL_NAME` / `CET_MODEL_API_KEY` | 本环境无真实模型凭据，**未验证** | 待验 |

> 说明：唯一约束冲突时 Hibernate 仍会打印一行 `ERROR ... Unique index or primary key violation` 日志，内容是重复的**用户名**，不含口令或其哈希；该行来自框架的 SQL 异常记录，属预期噪声。


### 12.1 写作批改实测（2026-11-20）

使用 Amazon Corretto 21.0.13.1、Maven Wrapper 3.9.16 和 Spring Boot 3.5.16。`./mvnw -o -B clean verify` 构建成功，全量 **161 项通过**，0 失败、0 错误、0 跳过。新增回归为 demo 4 项和 live 模拟上游 18 项。

| 验证方式 | 实测结果 |
|---|---|
| 启动 demo JAR，通过实际 HTTP 注册、登录并依次提交两稿 | 草稿 `200`，128 词，两条 `GRAMMAR` 问题。逐字定位到 `Many students prefers` 和 `Last semester, our class organize a weekend cleanup`。`exercise.issueIndex=0` 指向第一条，题面换到动物收容所，要求给复数主语 `The volunteers` 选择 `feeds / feed`。 |
| 同一学生提交修订稿 | 将 `prefers` 改成 `prefer`、`organize` 改成 `organized` 后返回 `200`。总体反馈更新，`issues` 为空，响应省略 `exercise`。只改其中一处的组合返回 `422 DEMO_UNSUPPORTED`，提示限于内置两稿。空白作文返回 `400 INVALID_INPUT`，匿名请求返回 `401 UNAUTHORIZED`。 |
| 启动 live JAR，对接本地模拟 Chat Completions 上游 | 自选自行车道题返回 `200`，两条引用可逐字定位，练习通过下标 1 关联第二条问题。模拟上游返回空问题列表时响应省略练习。只出现在题目里的引用、越界下标和上游 HTTP 500 均返回 `502 MODEL_ERROR`，读取超时返回 `504 MODEL_TIMEOUT`。 |
| 真实模型 | 本次环境未配置真实模型凭据。真实供应商的鉴权、实际批改质量、对合理不同表达的判断及练习与知识点的匹配尚未实测。 |


### 12.2 写作练习判答与换题复测实测（2026-11-20）

使用 Amazon Corretto 21.0.13.1 和 Maven Wrapper 3.9.16，运行 `./mvnw -o -B clean verify` 构建成功。全量 **180 项通过**，0 失败、0 错误、0 跳过，包含本次新增的 demo 5 项和 live 模拟上游 14 项。

| 验证方式 | 实测结果 |
|---|---|
| 实际启动 demo JAR，通过 HTTP 注册、登录、批改和判答 | 原批改草稿和修订稿的响应与第 6.10 节一致。取草稿的原问题和练习，完整 `feed` 句和时间状语前置的表达判为 `CORRECT`，`feeds` 和只填 `feed` 分别说明主谓错误和未完成整句，均为 `NEEDS_REVISION`。 |
| 同一学生换到学校电台复测 | 出题要求复数主语和一般现在时。提交 `The student hosts check…`、`The student hosts checks…` 和单数句 `The station opens…`，分别得到 `TARGET_USED`、`SAME_ERROR`、`INSUFFICIENT_EVIDENCE`。前两条引用逐字来自各自答案，第三条无引用。另一个正确表达也得到 `TARGET_USED`。实测空白、匿名和未收录答案时分别返回 400、401 和 422。 |
| 实际启动 live JAR，对接本地模拟 Chat Completions 上游 | 用自选的一般过去时问题验证上下文传递、原练习判答、新题生成、三种复测结果和原文引用。引用旧作文、复用原题、上游 HTTP 500 返回 `502 MODEL_ERROR`，上游读超时返回 `504 MODEL_TIMEOUT`。自动回归另外覆盖错误的字段类型、改动引文空格和证据不足时附带引文。 |
| 真实模型 | 当前环境未配置真实模型凭据。真实供应商鉴权、不同合理答案的判定质量、新题与具体知识点的匹配，以及对模糊答案的证据判断待真实模型联调。 |


### 12.3 live 冒烟实测（2026-11-20）

使用 Amazon Corretto 21.0.13.1（`$HOME/cet-tools/amazon-corretto-21.jdk/Contents/Home`）、Maven Wrapper 3.9.16 和 Spring Boot 3.5.16。

| 验证方式 | 实测结果 |
|---|---|
| 全量回归 | `JAVA_HOME=... ./mvnw -o -B verify`：`Tests run: 180, Failures: 0, Errors: 0, Skipped: 0`，`BUILD SUCCESS`；仍使用独立内存库/临时文件库和环回端口。 |
| 单文件冒烟 | `python3 scripts/live_smoke.py --stand-in`：固定响应替身 + 临时文件 H2 + 环回端口启动 jar，**15 通过 / 0 失败 / 0 跳过**，退出码 0。 |
| 覆盖的契约步骤 | 阅读：`reading.word`、`reading.sentence`；汉译英：三类提示 `translation.hint_keyword/phrase/structure`、三份译文批改 `translation.review_draft/improved/alternative`；写作：`writing.review`、`writing.exercise_ok/bad`、`writing.retest_create`、`writing.retest_ok/bad/weak`。 |
| 样本与 demo 的区别 | 使用博物馆延长开放时间、桥维修改乘渡轮、社区图书馆退休居民、学校实用技能课程四组原创材料，均不在 demo 内置目录里；若误跑 demo 会在第一步因 `mode` 不是 live 或 `422` 失败。 |
| 真实模型 | 本次未提供真实凭据，未访问任何付费模型，也未搜取本机其他项目凭据；替身结果**不是真实模型验证**。真实模型的释义、批改、判答与合理不同表达的接受度仍待按 [3.3.1](#331-live-冒烟入口契约检查与人工判断分开) 人工验收。 |

> 本轮只新增 `server/scripts/live_smoke.py` 与 README 说明；未改 `android/`、未新增后端接口或存储、未改认证/401/API，也未执行 git init 或提交。


### 12.4 词表兜底、评分档位与健康探测实测（2026-11-21）

使用 Amazon Corretto 21.0.13.1（`$HOME/cet-tools/amazon-corretto-21.jdk/Contents/Home`）、Maven Wrapper 3.9.16、离线模式 `./mvnw -o test`。

| 验证方式 | 实测结果 |
|---|---|
| 全量回归 | `Tests run: 194, Failures: 0, Errors: 0, Skipped: 0`，`BUILD SUCCESS`。本轮新增：`HealthApiTest` 3 项、`DemoExplanationWordFallbackApiTest` 4 项、`ExplainLiveApiTest` 扩展字段/健康探测 3 项、翻译 demo 评分 1 项、翻译 live 可选评分 1 项、写作 demo 评分 1 项、写作 live 可选评分 1 项。 |
| 健康探测 | `GET /api/v1/health` 匿名 `200`，响应固定 `{"status":"UP","mode":"demo","version":"0.2.0"}`；伪造凭证仍 `200`；live 配置下 `mode=live`（`ExplainLiveApiTest`）。`SecurityConfig` 已单独放行该 GET。 |
| 词表兜底 | 非精调句中查 `decline`、`bookstore`、`the` 等常用词返回 `200`、`contextual=false`，并带 `phonetic`/`partOfSpeech`/`lemma`/`cetLevel`；`students → student`、`closes → close` 能还原原形；词表外生词（如 `quizzacious`）仍 `422 DEMO_UNSUPPORTED`。 |
| 评分与参考译文 | demo 汉译英：草稿 9、补全稿 12、准确/另一种译法 14，`scoreMax=15`，`scoreBand` 与参考译文非空；demo 写作：草稿 10、修订稿 14。live 模型未返回评分时字段省略（JSON 中不出现），返回时原样透传，均不影响成功响应。 |
| 兼容性 | 五个释义新增字段与四个批改新增字段全部 `@JsonInclude(NON_NULL)`，未返回时不出现；旧断言「恰好三字段/五字段」的场景已同步更新为契约全字段集合。 |

---

## Android 阶段验收记录（0.2.0 ~ 0.8.0）

## 0.2.0 图片来源与导入实测

四张图片都是本机用 Pillow 和 Arial 排版生成的实际 JPEG 文件。相册联调把文件放入模拟器媒体库，再由系统选择器返回图片 URI，交给 ML Kit 实际识别。

- [decline-examples.jpg](qa/decline-examples.jpg) 含两条后端 demo 例句和同段的其他句子。较长的第一段可验证第二处 `decline` 位于首屏外。
- [river-passage.jpg](qa/river-passage.jpg) 含河流和花园的新句子，用于校对、保存和验证 demo 未收录。
- [blank-page.jpg](qa/blank-page.jpg) 是空白页。
- [camera-page.jpg](qa/camera-page.jpg) 用于 Android Emulator 后摄像头的图像输入。

可在 `android/` 下把三张相册图片加入模拟器媒体库。以下命令使用在 PATH 中的 `adb`，多设备时指定模拟器序列号。

```sh
adb shell mkdir -p /sdcard/Pictures/CetOcrQa
for photo in decline-examples.jpg river-passage.jpg blank-page.jpg; do
  adb push "qa/$photo" "/sdcard/Pictures/CetOcrQa/$photo"
  adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE \
    -d "file:///sdcard/Pictures/CetOcrQa/$photo"
done
```

| 场景 | 结果 |
| --- | --- |
| APK 构建与安装 | `assembleDebug`、`lintDebug` 和 APK 签名校验通过。Lint 0 个错误、7 个警告。0.2.0 已通过 `adb install -r` 安装到模拟器。 |
| 相册与本机 OCR | 在飞行模式且无默认路由时选择 decline JPEG，识别出两条例句。校对并保存后直接打开。新版重排识别结果保留了测试页的两个段落。 |
| 多句段落与词组 | 同一导入材料的两处 `decline` 分别返回「下降」与「婉拒」。原生长按拖选 `lower demand` 返回「较低的需求」。 |
| 收藏与冷启动定位 | 收藏第二处 `decline`，Room 保存区间 `[879, 886)` 及完整原句 `Some students decline the invitation.`。强制停止 App 后从生词本回原文，自动滚动到首屏之外的第二段，仅高亮第二处。 |
| 新材料 | 从河流 JPEG 识别后，在校对页改标题、添加正文与换行。保存后查 `river` 显示 demo 422 未收录提示。强制停止 App 后可从「我的材料」重新打开，并保留校对改动。 |
| 取消与空白图 | 取消系统选择器、修改识别结果后取消、导入空白图，三项均无新增文章。空白图提供「重拍」和「重选图片」，已通过重选完成后续导入。 |
| Room 实际版本升级 | 用上一版 APK 先收藏示例第二段的 `decline`，再原地安装 0.2.0。Room 从 v1 升到 v2，升级前后的已有收藏字段一致，旧记录仍能回原文并高亮第二段。 |
| 切换账号 | B 的「我的材料」和生词本均看不到 A 的内容。切回 A 后，可打开原文章并找到原收藏。 |
| 切换服务端地址 | 通过 ADB reverse 用不同地址访问同一后端，`/me` 返回相同学生 ID。换地址后文章和收藏均为空，切回原地址可见原记录。 |
| 相机入口 | 「拍照导入」打开 Android 系统相机 `com.android.camera2`，进入拍摄界面。 |
| 模拟器拍摄 | 后摄通过 `imagefile` 读取 `camera-page.jpg`。在系统相机实按快门和确认，相机返回照片。断网 OCR 得到 `The river reflects the morning light.`，校对确认后保存并打开。 |
| 实体手机拍摄 | 待验。本次 ADB 只连接了模拟器。 |

模拟器拍摄时，在 `android/` 下启动带图像输入的 AVD。将 `CetStudy_API35` 换成已有 AVD 的名称即可复用测试图片。

```sh
"$ANDROID_HOME/emulator/emulator" -avd CetStudy_API35 \
  -camera-back "imagefile:$PWD/qa/camera-page.jpg"
```

测试长文有个别字母大小写误识，校对页可修正。Android 15 ARM64 模拟器覆盖了上述联调。实体设备相机及厂商差异、旧 Android 的系统文档选择器回退、其他设备分辨率和后端 live 模式仍待验证。


## 0.3.0 原句填空

1. 登录后打开「生词本」，点击「原句填空」。收藏为空时，生词本会提示先去阅读页收藏。
2. 每轮从当前服务根地址和当前学生的 Room v2 收藏读取题目。题干使用收藏的 UTF-16 区间，只挖空当时选中的位置。题目标题如果含答案，作答前会遮盖该词或词组。
3. 输入单词或词组并提交。核对忽略英文大小写和首尾空白，保留中间的空格。提交或查看答案后可阅读释义和完整原句，并可回到收藏的位置。原文页的「返回练习」会回到刚才的题目。
4. 一轮依次使用每条收藏一次。只有先提交并答对才计入独立答对，答错或查看答案都计为 0。最后显示独立答对数和总题数。打开生词本后，断开网络仍能进入和完成练习。

回归测试在 `app/src/test/java/com/cethelper/android/ClozePracticeTest.java` 与 `ClozeRoundCreationTest.java`。前者覆盖同句同词第二处、重复原句、词组、大小写与首尾空格、标题遮挡、无效位置、查看答案及答错后的计分；后者覆盖成局时的容错与抽样：引用了已删除文章、偏移量错位、原句已不在正文的「脏生词」会被安静跳过（只计入 `skippedCount`），全部脏数据时返回空局而不是抛异常，收藏超过 10 条时按 `MAX_ROUND_SIZE` 抽样成 10 题，`maxSize` 参数生效，抽样后的题目仍可正常作答计分。

### 0.3.0 实测结果

使用 Apple Silicon Mac mini 与 Android 15（API 35、ARM64）Pixel 6 配置的模拟器。0.3.0 通过 `adb install -r` 装在已有 0.2.0 数据的模拟器上。运行了 7 项 JVM 回归测试，构建及 APK 签名校验通过，Lint 为 0 个错误、7 个警告。

| 场景 | 结果 |
| --- | --- |
| 真实收藏和飞行模式 | A 的两题分别来自旧示例收藏和此前经系统相册、OCR、真实 demo 后端建立的 `Decline Page QA` 收藏。登录进入生词本后开启飞行模式并关闭 Wi-Fi、移动数据。在无默认网络路由的情况下开始并完成两题，两题都可回原文并高亮收藏位置。 |
| 答题前界面与计分 | 检查两道真实题的可见文字和无障碍节点。题干挖空，标题里的答案被遮挡，答案、释义、完整原句和回原文入口在作答前隐藏。导入题输入带首尾空格的大写 `DECLINE` 判对，示例题点击查看答案，结果为「独立答对 1 / 2 题」。 |
| 切换账号和空生词本 | 退出 A 并通过真实后端注册 B。B 开始时生词本为空，入口禁用并提示先去阅读页收藏。 |
| 切换服务根地址 | 用 ADB reverse 经另一地址登录同一后端，`/me` 返回相同学生 ID。换地址后练习入口显示空收藏提示，切回原地址可再次看到 B 的两题。 |
| 同句同词第二处 | 为 B 在模拟器 Room 中插入标明「[QA 测试数据]」的本地文章和收藏，保存第二处 `decline` 的位置。原句是 `To decline an offer, you can decline politely.`，实际题干是 `To decline an offer, you can ____ politely.`。回原文时仅第二处高亮。该记录仅用于模拟器验证，APK 使用正常的收藏数据路径。 |
| B 的词组题和分数 | 在示例文章长按拖选并通过真实后端查释义，收藏 `lower demand`。本轮只出现 B 的词组题和测试题。测试题答错后显示答案、释义和原句。词组题填入带首尾空格的 `LoWeR dEmAnD` 判对，总分为「独立答对 1 / 2 题」。 |
| 数据保留 | 核对练习前后 4 条已有收藏的来源、选区、原句和短释义，结果一致。原先的 4 篇导入文章均保留，数据库版本为 Room v2。 |

## 0.3.1 原句填空旋转恢复

横竖屏切换导致 Activity 重建时，当前进程会保留本轮题目顺序、题号、未提交的输入、判分或揭晓状态及累计得分。重建后按新方向重新创建界面。已经判分的题会直接显示结果。键盘弹出时题号固定在顶部，题干和输入区可滚动。恢复要求当前保存的服务端地址和凭证仍与本轮匹配。冷启动继续请求 `/me` 验证会话，401 仍会清除失效凭证。 [Android Activity 配置变更](https://developer.android.com/reference/android/app/Activity#onRetainNonConfigurationInstance())

### 重建回归测试

`ClozeRecreationTest` 使用 AndroidX `ActivityScenario.recreate()` 重建实际 Activity，覆盖在线重建、服务端拒绝凭证时继续同一轮、草稿和已判分状态、最终分数、冷启动 401，以及更换凭证时的账号隔离。测试用临时本地服务和独立 Room 账号。 [AndroidX ActivityScenario](https://developer.android.com/reference/androidx/test/core/app/ActivityScenario#recreate())

从 `android/` 运行以下命令可保留模拟器应用数据。

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -e class com.cethelper.android.ClozeRecreationTest \
  com.cethelper.android.test/androidx.test.runner.AndroidJUnitRunner
```

本机 Android Gradle Plugin 的 `connectedDebugAndroidTest` 会在结束时卸载目标应用。保有测试数据的设备请使用上面的 ADB 方式。

### 模拟器验收

2026-11-19，在 Pixel 6 配置的 Android 15（API 35、ARM64）模拟器上使用本地 demo 后端。用 0.3.0 实际收藏第二段的 `decline` 和词组 `lower demand`，然后以 `adb install -r` 升级到 0.3.1。两条收藏升级前后一致。

| 场景 | 结果 |
| --- | --- |
| 飞行模式与半截答案 | 第 1 题点击查看答案。第 2 题输入 `lower`，在键盘仍打开时开启飞行模式并关掉 Wi-Fi、移动数据。由竖屏转横屏后仍是第 2 题，输入保留为 `lower`，题号可见。 |
| 判分后再次旋转 | 横屏补全 `lower demand` 并提交，旋回竖屏后仍显示第 2 题「答对了」，无再次提交入口。结束显示「独立答对 1 / 2 题」，全程保持飞行模式。 |
| 联网旋转 | 确认模拟器可访问实际后端，在第 2 题输入 `lo`。竖屏转横屏、再转回竖屏，进程、题号及草稿均保留。 |
| 冷启动校验 | 在同样的飞行模式配置下验证后端不可达。强制停止后冷启动仍显示连接错误。恢复网络并重试，完成服务端会话校验后回到首页。 |
| 自动回归 | 同一个重建测试在 0.3.0 上因练习页消失而失败。0.3.1 的 3 项设备测试与 7 项 JVM 测试均通过。Lint 有 0 个错误、9 个警告。 |

初次运行 Gradle 设备任务后，模拟器旧安装被卸载，旧测试数据被清除。上述验收使用随后重新建立的临时账号及真实收藏，并以 ADB 覆盖安装和运行测试。

## 0.4.0 整句翻译与长难句拆解

在阅读页点词或选中词组后，点词语面板中的「分析整句」。入口在有原句的选区选定时即出现。即使 demo 模式未收录该词，仍可分析所在句子。

App 向当前服务端的 `POST /api/v1/sentence-analyses` 发送选区对应的原句及当前凭证，保留句中空格和标点。面板显示原句、中文翻译、句子主干，以及逐段的原文、语法作用和说明。demo 成功结果标为「演示分析」。点击片段会按返回顺序定位，并将高亮处滚入面板视野。 [服务端整句接口](../server/README.md#67-post-apiv1sentence-analyses)

切换选区后，上一句的响应即使迟到也不会覆盖当前面板。未收录、连接失败或超时时保留原句，面板内可重试。「返回释义」可继续查看本次选词的结果或收藏。「关闭」回到选词时所在的阅读位置。

### 构建与回归

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w com.cethelper.android.test/androidx.test.runner.AndroidJUnitRunner
```

本次构建、签名校验和覆盖安装通过。JVM 测试 9 项、ADB 设备测试 4 项通过，Lint 为 0 个错误、10 个警告。双 `that` 用固定的六片段响应测试。设备测试依次点击两个文本都为 `that` 的片段，读取原句的高亮区间，验证两次分别落在第一处和第二处。JVM 测试另核对片段间双空格、末尾句号和重复片段缺失的处理。

### 模拟器实测

使用 Android 15（API 35、ARM64）Pixel 6 配置模拟器，默认地址 `http://10.0.2.2:8080`。后端为本机启动的 `cet-server-0.1.0.jar`，启用真实 demo 目录。

| 场景 | 结果 |
| --- | --- |
| 内置第一句 | 点 `decline` 后分析，显示「这份报告把这次下降归因于需求走低。」、主干和 4 个片段。点介词短语可高亮，返回释义后仍显示「下降」。 |
| 内置第二句与未收录词 | 点 `invitation` 显示词条未收录，仍可点「分析整句」。断网请求失败后原句保留；恢复网络点「重试」，显示「一些学生谢绝了这份邀请。」、主干和 3 个片段。 |
| README 长句 | 通过相册导入进入校对页，填入服务端 README 第 6.7 节原句并保存。点 `While` 后分析，显示实际 demo 译文、主干及全部 6 个有序片段。点第 6 段，面板滚回原句并高亮 `that reading widely is what really builds vocabulary`。 |
| 未收录整句与阅读位置 | 滚动文章后分析 `A quiet dog  sleeps at noon.`，实际 demo 返回 422。原句的两个内部空格及句号保留。点重试后仍可关闭面板，回到原先已滚动的位置。 |
| 乱序与超时 | 临时测试代理延迟内置第一句的真实响应，切到第二句后先显示第二句。释放第一句响应，面板仍显示第二句。代理返回一次固定 504 时，面板保留第二句并提示超时，重试取得真实 demo 译文。 |
| 原有路径 | 长按选中 `lower demand` 仍可收藏，生词本可开始原句填空。 |

## 0.5.0 汉译英练习

1. 从首页打开「汉译英」。可以输入或粘贴中文、使用演示材料，也可以拍照或从相册选图。图片走系统相机或选择器，再使用 APK 内置的 ML Kit 中文模型识别，校对文字和标点后开始作答。首页「我的材料」里的英文导入仍使用英文模型。 [ML Kit 中文文字识别](https://developers.google.com/ml-kit/vision/text-recognition/v2/android?hl=zh-cn)
2. 作答页显示中文原文与可编辑的英文草稿。点击「关键词」「短语」或「句子结构」才发出对应请求，界面只显示最后选择的类型。旋转屏幕会恢复当前练习，包括原文和草稿。断网时可再次点提示按钮，或在批改区域点「重试」。
3. 提交后查看总体反馈和逐项建议。点击「定位引文」会打开本次提交的中文原文或英文译文。各项独立定位，同一引文出现多次时显示次数并高亮所有候选位置。「返回修改草稿」可继续作答，重新提交会用新草稿请求批改。空问题列表直接显示服务端的总体反馈。
4. 「使用演示材料」填入服务端接口文档里的内置中文原文。demo 的提示只收录这段材料，批改只收录内置段落和预设译文的逐字匹配组合。遇到 demo 未收录会显示范围并保留输入。联调可复制接口文档中的两份 `studentTranslation`。 [提示契约](../server/README.md#68-post-apiv1translation-hints) [批改契约与译文样例](../server/README.md#69-post-apiv1translation-reviews)

### 复验

将测试 JPEG 导入模拟器相册，通过「汉译英 → 相册导入」选取，再校对至与服务端内置原文一致。图中文字是内置材料的排版图。 [中文段落测试图](qa/translation-passage.jpg)

```sh
adb shell mkdir -p /sdcard/Pictures/CetTranslationQa
adb push qa/translation-passage.jpg /sdcard/Pictures/CetTranslationQa/translation-passage.jpg
adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE \
  -d file:///sdcard/Pictures/CetTranslationQa/translation-passage.jpg

./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w com.cethelper.android.test/androidx.test.runner.AndroidJUnitRunner
```

### 模拟器验收

2026-11-20，Pixel 6 配置，Android 15（API 35、ARM64）。实际 demo 使用本机启动的服务端 JAR，模拟器通过 `http://10.0.2.2:8080` 连接。

| 来源 | 验证结果 |
| --- | --- |
| 模拟器本机 OCR | 系统相册读取测试 JPEG。中文段落可读，个别汉字和标点识别有误。通过校对页编辑后，作答页显示校正的完整中文段落。 |
| 模拟器系统相机 | 后摄映射到同一段落的图片，在飞行模式按快门并确认，照片经本机 OCR 进入中文校对页，可确认至作答。模拟器相机画幅裁掉了部分文字，完整段落以相册导入验收。 |
| 英文导入回归 | 从首页「我的材料」打开系统相册，选择英文测试图，识别出 `decline` 并进入带标题和保存按钮的原有校对页。 |
| 实际 demo 服务 | 分别请求关键词、短语和句子结构。提交文档首稿，得到「对高校毕业生」「越来越」两条漏译与 `have good team cooperation` 一条表达问题。三条引文均能打开并定位到对应的提交文本。填入修订稿后重新提交，返回新总体反馈及 0 条问题。 |
| 断网、旋转与实际 demo 重试 | 首稿写完后关 Wi-Fi 和移动数据，提交显示连接错误。旋转至横屏确认草稿完整，恢复竖屏和网络后点「重试」，实际 demo 返回 3 条问题。 |
| 实际 demo 未收录 | 改动样例原文请求提示，以及提交未收录的译文，均显示各自的 demo 范围并保留原文和草稿。 |
| 固定 HTTP 响应 | `TranslationPracticeTest` 延后关键词响应，让最后点击的结构提示先返回，Activity 重建并收到较早的结果后仍显示结构提示与原草稿。批改按「明天」「今天」的乱序定位，校验 3 个中文候选位置与 2 个英文候选位置的高亮区间。请求发出后改草稿仍可定位首稿，重交后显示修订稿对应的反馈。 |
| 回归与打包 | Android JVM 测试 9 项、模拟器测试 5 项、服务端 Maven 测试 139 项通过。Lint 0 个错误、10 个警告。Debug APK 签名校验和覆盖安装通过。 |

[Debug APK](app/build/outputs/apk/debug/app-debug.apk)

## 0.6.0 写作练习

1. 从首页打开「写作练习」，输入或粘贴作文题目和英文作文。「使用演示草稿」填入校园志愿服务题和完整三段草稿。客户端保留段落、标点和内部空格，沿用当前账号与服务地址提交至 `POST /api/v1/writing-reviews`。
2. 批改按服务端顺序展示总体反馈、问题类别、引用、原因和局部修改建议。点击「定位引文」查看提交时的作文。当前草稿已改动时，仍可定位上一次批改的原文。重复引文会显示出现次数并高亮所有候选位置。
3. 小练习显示在 `exercise.issueIndex` 对应的问题卡片内。填写的答案在本轮练习中暂存，接口无判分结果。再次提交成功后使用新稿的反馈，有总体反馈且问题为空时会收起旧问题和小练习。
4. 旋转及断网重试保留题目、草稿和本轮答案。修改后可提交新稿，晚到的旧请求结果不会覆盖新反馈。demo 仅匹配第 6.10 节的内置草稿和修订稿，未收录时会提示范围并保留输入。 [接口契约与演示材料](../server/README.md#610-post-apiv1writing-reviews)

### 构建与验收

从 `android/` 运行构建和回归。使用 ADB 覆盖安装可保留设备数据。

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w com.cethelper.android.test/androidx.test.runner.AndroidJUnitRunner
```

2026-11-20，Pixel 6 配置的 Android 15（API 35、ARM64）模拟器。实际 demo 使用本机当前服务端 JAR，模拟器连至 `http://10.0.2.2:18091`。

| 来源 | 验证结果 |
| --- | --- |
| 实际 demo 服务 | 从首页进入写作，载入校园志愿服务题和三段草稿。首稿显示「演示批改」与两条语法问题。两条引用 `Many students prefers` 和 `Last semester, our class organize a weekend cleanup` 均在本次作文中高亮，小练习位于第一条问题。 |
| 实际 demo 的修订、旋转与重试 | 在作文框用键盘将 `prefers` 改为 `prefer`，将 `organize` 改为 `organized`。断网提交后转横屏，题目、修订稿和练习答案均保留。恢复网络重试后显示修订稿的总体反馈，0 条问题，旧练习消失。 |
| 实际 demo 未收录 | 仅改对一个动词的作文返回 `422 DEMO_UNSUPPORTED`。界面提示演示范围，保留题目与当前作文。 |
| 固定 HTTP 响应 | `WritingPracticeTest` 提交自选题目，让练习指向第二条问题。编辑题目和作文并重建 Activity 后，练习答案保留。点击两条引用仍会打开首稿，重复引文标明 2 次，并校验两个候选区间的 UTF-16 高亮。延迟中间稿的响应，验证它晚于修订稿返回时不会覆盖新反馈。 |
| 构建与回归 | Android JVM 测试 9 项、模拟器测试 6 项、服务端 Maven 测试 161 项全部通过。Lint 0 个错误、10 个警告。Debug APK 已通过签名校验和覆盖安装。 |

[Debug APK](app/build/outputs/apk/debug/app-debug.apk)

## 0.8.0 离线续用与草稿

0.8.0（versionCode 9）继续使用 0.7.0 的 Room 数据、账号隔离和现有接口，新增两项可中断后续用的能力。从 0.7.0 覆盖安装时使用 `adb install -r`，不要清除应用数据。

1. 在线登录并成功请求 `/me` 后，客户端保存服务根地址、凭证指纹和学生身份。之后断网冷启动，只要缓存的地址与凭证仍匹配，就按同一学生打开本机「我的材料」、生词本、原句填空和回原文，并在顶部显示「离线」。恢复联网后客户端重新验证 `/me`；成功只清除离线提示，不离开当前页面，也不改写正在编辑的输入。服务端返回 401 时清除本机会话并回到登录页；主动退出、切换账号或修改服务地址同样不能看到其他账号的内容。
2. 汉译英的中文原文与英文草稿、写作的题目与英文草稿在输入时自动保存到本机。键由学生 ID、服务根地址组成（`studentId|serverUrl`），不以用户名作键。离开页面、结束进程或重开 App 后仍可继续；汉译英原文页显示「继续作答」并恢复两份内容。旋转、导入图片、使用演示材料、清空或替换草稿沿用原逻辑；确认清空后保存的是空值，重启不会恢复旧内容。该版本只保存可继续编辑的输入，不增加错题本、云同步或新接口。

### 0.8.0 复验步骤

先启动独立的 demo 后端，只监听本机环回地址，H2 工作库放在临时目录。然后在 `android/` 下构建；构建结束后回到项目根运行设备脚本，避免相对路径和默认 `adb` 解析错位。不要对共享模拟器或物理手机执行脚本中的清理动作。

```sh
# 项目根：启动独立 demo 后端
export JAVA_HOME="$HOME/cet-tools/amazon-corretto-21.jdk/Contents/Home"
QA_TMP="$(mktemp -d "${TMPDIR:-/tmp}/cet-0.8-server.XXXXXX")"
"$JAVA_HOME/bin/java" -jar server/target/cet-server-0.1.0.jar \
  --server.port=18088 \
  --server.address=127.0.0.1 \
  --cet.explanation.mode=demo \
  --spring.datasource.url="jdbc:h2:file:$QA_TMP/demo;DB_CLOSE_ON_EXIT=FALSE"
```

```sh
# android/ 下构建
cd android
export JAVA_HOME="$HOME/cet-tools/amazon-corretto-21.jdk/Contents/Home"
export ANDROID_HOME="$PWD/.local/sdk"
export GRADLE_USER_HOME="$PWD/.local/gradle"
export ANDROID_USER_HOME="$PWD/.local/android-user"

./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:assembleDebugAndroidTest --offline
cd ..
```

```sh
# 项目根：安装本版 APK 与测试 APK，并在隔离实例运行 instrumentation
export ADB="$PWD/android/.local/sdk/platform-tools/adb"
"$ADB" -s emulator-5570 install -r android/app/build/outputs/apk/debug/app-debug.apk
"$ADB" -s emulator-5570 install -r -t android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
"$ADB" -s emulator-5570 shell am instrument -w \
  com.cethelper.android.test/androidx.test.runner.AndroidJUnitRunner
```

本版实际运行结果为 `OK (7 tests)`，完整输出见 `android/.local/qa-0.8/instrumentation-20261120.log`。命令行显式设置 `--cet.explanation.mode=demo`，即使终端环境里存在 live 配置也按 demo 运行。覆盖安装保留应用数据；跨进程脚本只在开始时清理本应用数据，之后 instrumentation 仍可独立执行。

跨进程回归入口是 [qa/draft_process_qa.py](qa/draft_process_qa.py)。脚本要求调用者显式给出模拟器序列号和写在隔离实例上的标记文件；序列号不是 `emulator-`、缺少标记或标记不符时会拒绝运行，设备校验发生在所有诊断和网络恢复逻辑之外。`python -O` 会被拒绝，避免断言被移除后误装未授权设备。

```sh
# 项目根：写入隔离实例标记，再运行脚本
export ADB="$PWD/android/.local/sdk/platform-tools/adb"
"$ADB" -s emulator-5570 shell 'mkdir -p /sdcard/CetDraftQa && echo cet-08-draft-isolated-5570 > /sdcard/CetDraftQa/device-marker'

DRAFT_QA_SERIAL=emulator-5570 \
DRAFT_QA_DEVICE_MARKER=cet-08-draft-isolated-5570 \
DRAFT_QA_SERVER_URL=http://10.0.2.2:18088 \
DRAFT_QA_SERVER_LOCAL=http://127.0.0.1:18088 \
python3 android/qa/draft_process_qa.py
```

脚本在隔离实例上覆盖安装当前 Debug APK，清理本应用包的数据，注册两个本轮独占账号，并通过真实界面写入文章、收藏、汉译英原文与译稿、作文题目与稿件。第二账号在加入新原文前先核对本账号范围的持久存储，再通过新原文进入作答页直接确认英文译稿为空，不使用会替换并清空译稿的演示入口；显式清空后的冷启动同样读取存储并进入作答页核对英文译稿为空。每次冷启动都执行 `am force-stop` 并确认旧 PID 已退出；不使用旋转或 `ActivityScenario.recreate` 代替进程结束。默认证据在 `android/.local/qa-0.8/process-<时间>/`。需要复跑时不必更换标记；脚本会按时间生成新账号，不会复用上轮账号。示例文章第二处 `decline` 的点击坐标默认按 Pixel 6 的 1080×2400 布局设置，换分辨率时用 `DRAFT_QA_DECLINE_TAP="x y"` 覆盖。`adb shell input text` 无法注入中文，因此新原文使用不含 A 原文的 ASCII 材料；该路径只调用 `setSourceInput`，不会像演示替换那样清掉译稿。

### 0.8.0 实测结果

2026-11-20，Apple Silicon Mac，Android 15（API 35、ARM64）隔离 AVD `emulator-5570`，demo 后端监听 `127.0.0.1:18088`（`--cet.explanation.mode=demo`），Debug APK 为当前源码构建产物。脚本实际输出 18 个 `PASS`，最后一次完整运行证据为 `android/.local/qa-0.8/process-1120083921/`；其中 `05b-account-b-empty-translation-draft.*` 与 `07-cleared-after-cold-start.*` 直接记录了新原文下的空英文译稿。本轮没有修改产品代码；验证未复现需要修复的 0.8 阻塞问题。

| 场景 | 结果 |
| --- | --- |
| 真实进程重启后的两类草稿 | 汉译英原文与译稿、作文题目与稿件分别保留；5 次 `am force-stop` 均先确认旧 PID 消失再重启。 |
| 账号隔离与切回 | 第二账号看不到第一账号的导入文章、收藏和两份草稿；加入新原文前先核对 B 的持久存储为空，再进入作答页直接确认英文译稿为空。切回第一账号只复查两类草稿仍在，文章与收藏已在前一次冷启动单独证实。 |
| 重连与会话复验 | 断网冷启动显示离线并读取缓存内容；恢复网络后 `/me` 重新验证成功，离线提示消失，停留在写作页且题目/稿件不变，随后打开汉译英确认原文与译稿仍保留。 |
| 服务端 401 | 离线冷启动后撤销服务端凭证，恢复网络触发复验，界面回到登录页并显示「会话已失效，请重新登录」；再次冷启动仍停留在登录页。 |
| 明确清空 | 汉译英原文与写作题目/稿件确认清空后，真实进程重启；读取本机存储并用新原文进入作答页，确认英文译稿为空，不恢复旧输入。 |
| 构建与回归 | JVM 测试 9 项、模拟器 instrumentation 7 项全部通过；Lint 0 个错误、11 个警告；Debug APK 通过 v2 签名校验，`versionName 0.8.0`、`versionCode 9`。 |
| 0.7.0 覆盖升级 | 上一轮已在隔离模拟器验证升级后保留原文和收藏；本轮脚本从干净 0.8.0 数据开始，未重复覆盖升级。 |

构建产物：[Debug APK](app/build/outputs/apk/debug/app-debug.apk)，SHA-256 `aecbcf0f5d3051e45e9d196e5b439065669e3d262bd3302c71cb5c7926567879`。

以下项目没有本轮实际证据，不能视为已通过：物理 Android 手机上的厂商相机、系统选择器、进程回收与输入法差异；真实模型（live）下的提示、批改和写作质量；Android 8–14 及非 Pixel 分辨率的设备。真实模型需在有授权的 `CET_MODEL_*` 配置下验证，物理手机由用户本人验收。

