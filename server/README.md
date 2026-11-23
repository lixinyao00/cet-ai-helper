# 四六级 AI 学习助手 · 服务端（cet-server）

基于 **Java 21 + Spring Boot 3.5.16 + Spring Security + JPA** 的学习服务网关，负责学生账号与无状态 Bearer 会话管理，以及阅读释义、长难句拆解、OCR 整理、词典联想/查词、AI 助教 SSE 流式问答、汉译英和写作批改/复测能力。

> 完整接口入参、错误码字典与 `curl` 示例见 [`docs/api-contract.md`](../docs/api-contract.md)。

## 1. 模块与核心设计

- **认证与安全（`auth/`）**：密码使用 BCrypt 哈希存储，防账号枚举（用户不存在时同步执行假比对拉平耗时）；下发 `SecureRandom` 32 字节不透明 Token，服务端仅保存 **SHA-256 摘要** 与 TTL（默认 72 小时），登出立即撤销。
- **双模式推理（`explain/`、`sentence/`、`article/`、`dictionary/`、`translation/`、`writing/`）**：
  - `demo` 模式（默认）：内置覆盖全流程的精选语料，无需 API Key 即可启动联调与自动化测试。
  - `live` 模式：通过兼容 OpenAI Chat Completions 的 `ChatCompletionsClient` 调用上游模型，对 JSON 结构、原文连续片段引用和注入防护做严格校验，失败脱敏映射为 `502 MODEL_ERROR` / `504 MODEL_TIMEOUT`。
- **AI 助教流式引擎（`tutor/`）**：使用 `spring-webflux` 消费上游 SSE 流并经 Servlet `SseEmitter` 按 `meta` → `delta`×N → `done` / `error` 推送给客户端。
- **持久化（H2 / 可选 MySQL）**：默认使用文件型 H2（`server/data/cet`），支持通过 `mysql` profile 切换至 MySQL 8.0+，并提供一次性只读校验迁移工具 [`tools/MigrateH2ToMysql.java`](tools/MigrateH2ToMysql.java)。

## 2. 环境与启动

需要 **JDK 21**（仓库内置 Maven Wrapper `./mvnw`）。

### Demo 模式（默认，零配置）

```sh
cd server
./mvnw -B spring-boot:run
# 或先打包再运行
./mvnw -B package
java -jar target/cet-server-0.1.0.jar --server.address=127.0.0.1 --server.port=8080 --cet.explanation.mode=demo
```

健康探测：`curl http://127.0.0.1:8080/api/v1/health` → `{"status":"UP","mode":"demo","version":"0.2.0"}`。

### Live 模式（对接真实大模型）

```sh
export CET_EXPLANATION_MODE=live
export CET_MODEL_BASE_URL=https://api.openai.com/v1
export CET_MODEL_NAME=gpt-4o-mini
export CET_MODEL_API_KEY=sk-REPLACE_ME
java -jar target/cet-server-0.1.0.jar
```

可用 [`scripts/live_smoke.py`](scripts/live_smoke.py) 对 `live` 契约进行自动化冒烟（支持 `--stand-in` 启动本地模拟上游做免密钥契约校验）。

### 可选 MySQL Profile

建表脚本位于 [`db/mysql/schema.sql`](db/mysql/schema.sql)，配置详见 [`src/main/resources/application-mysql.yml`](src/main/resources/application-mysql.yml)：

```sh
export SPRING_PROFILES_ACTIVE=mysql
export CET_DB_URL='jdbc:mysql://127.0.0.1:3306/<schema>?characterEncoding=UTF-8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true'
export CET_DB_USERNAME='<app_user>'
export CET_DB_PASSWORD='<password>'
./mvnw spring-boot:run
```

## 3. 配置项速查

| 环境变量 | 配置项 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `SERVER_PORT` | `server.port` | `8080` | 服务监听端口 |
| `CET_TOKEN_TTL` | `cet.auth.token-ttl` | `PT72H` | 会话凭证有效期 |
| `CET_EXPLANATION_MODE` | `cet.explanation.mode` | `demo` | `demo` 或 `live` |
| `CET_MODEL_BASE_URL` | `cet.explanation.base-url` | 空 | live 模式 OpenAI 兼容基地址 |
| `CET_MODEL_NAME` | `cet.explanation.model` | 空 | live 模式模型名 |
| `CET_MODEL_API_KEY` | `cet.explanation.api-key` | 空 | live 模式密钥 |
| `CET_MODEL_CONNECT_TIMEOUT` | `cet.explanation.connect-timeout` | `PT3S` | 上游连接超时 |
| `CET_MODEL_READ_TIMEOUT` | `cet.explanation.read-timeout` | `PT20S` | 常规模型读取超时 |
| `CET_ARTICLE_READ_TIMEOUT` | `cet.article.read-timeout` | `PT45S` | 文章整理单次上游读超时 |

## 4. 测试

```sh
cd server
./mvnw -B test
```

全量测试包含 **233 项**用例（认证与反枚举、严格 JSON 类型、Token 过期、H2 持久化、各业务 Demo 回归，以及基于 `FakeOpenAiServer` 的 Live 契约、超时与脱敏测试）。历史实测记录见 [`docs/verification-notes.md`](../docs/verification-notes.md)。
