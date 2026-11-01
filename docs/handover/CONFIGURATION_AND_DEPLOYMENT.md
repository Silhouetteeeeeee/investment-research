# 配置与部署

> 本文只写配置项名称与配置方式，**不包含任何真实密钥、口令或令牌**。仓库中的 `.env` 被 `.gitignore` 忽略，`.env.example` 只有占位符。

## 1. 配置来源总览

| 配置来源 | 文件/位置 | 说明 |
| --- | --- | --- |
| Spring 配置 | `app/src/main/resources/application.yml` | 主配置，所有 `spring.*` 项 |
| 环境变量 | `APP_API_TOKEN`、`DEEPSEEK_API_KEY`、`BAIDU_API_KEY`、`XQ_TOKEN`、`APP_DATA_DIR`、`APP_SKILLS_DIR`、`APP_MEMORY_IMPORT`、`OTEL_*` | 通过 `${...}` 注入 `application.yml` 或 `Environment`/`@Value` 读取 |
| 上游地址 | `app.datacenter-url`、`app.announcement-url`、`app.finance-url`、`app.f10-profile-url`、`app.f10-segments-url`、`app.stock-list-url` | **默认值写在适配器的 `@Value("${app.xxx-url:<默认URL>}")` 里，`application.yml` 没有这几项**；可用同名环境变量/系统属性覆盖（relaxed binding） |
| MyBatis-Plus | `application.yml` 的 `mybatis-plus.*` | 下划线转驼峰、主键 `auto` |
| Compose | `compose.yaml` + `.env` | 容器环境变量、卷、端口 |
| 镜像 | `Dockerfile` | 多阶段构建、非 root 用户、HEALTHCHECK |

## 2. 环境变量

| 变量 | 必填 | 默认值 | 作用 | 代码位置 |
| --- | --- | --- | --- | --- |
| `APP_API_TOKEN` | 是 | 无（缺失/空白 → 启动失败 `APP_API_TOKEN 未配置`） | 页面与 `/api/**` 的访问令牌，`Authorization: Bearer <token>` | `app/.../security/ApiTokenProperties.java` |
| `DEEPSEEK_API_KEY` | 运行时需要 | 空 | 模型调用密钥；为空时应用照常启动，但引擎换成 `UnavailableEngine`，`/api/ask` 返回错误事件 | `app/.../config/AssistantConfig.java`（`MISSING_KEY`） |
| `BAIDU_API_KEY` | 否 | 空 | 百度千帆 AI 搜索密钥，`web_search` 优先使用（每日 100 次免费）；为空或调用失败时回落 `DEEPSEEK_API_KEY`（DeepSeek Search 按次计费） | `data/.../config/SearchConfig.java` |
| `XQ_TOKEN` | 否 | 无 | 设计文档中的雪球 `xq_a_token`。**当前 Java 代码没有任何地方读取它**（见 §9） | 仅 `.env.example`、`compose.yaml`、`README.md` 出现 |
| `APP_DATA_DIR` | 否 | `./var` | 运行数据根目录：`app.db`、`runs/`、`sessions/`、`workspace/` | `app/.../Application.java`、`AssistantConfig`、`application.yml` |
| `APP_SKILLS_DIR` | 否 | `./skills` | `skills/<name>/SKILL.md` 所在目录 | `AssistantConfig.agentProperties` |
| `APP_MEMORY_IMPORT` | 否 | 空 | Python 版 `research_memory.db` 的**文件路径**；启动时若档案为空则一次性导入 | `app/.../config/MemoryImportRunner.java` |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | 否 | 空（= 不启用 tracing） | collector 根地址，代码自动补 `/v1/traces`；也可直接填完整地址 | `app/.../config/TracingConfig.java` |
| `OTEL_SERVICE_NAME` | 否 | `investment-research` | trace 的 `service.name` 资源属性 | 同上 |
| `OTEL_TRACES_SAMPLER_ARG` | 否 | `1` | 采样率 0..1；非法值按 1（全采样），不会拦住启动 | 同上 |
| `OTEL_SDK_DISABLED` | 否 | `false` | `true` 时不建 SDK，即使填了 endpoint 也不导出 | 同上 |
| `JAVA_TOOL_OPTIONS` | 否 | 镜像内 `-XX:MaxRAMPercentage=75` | JVM 参数 | `Dockerfile`、`compose.yaml` |

Tracing 只导出 traces（`invoke_agent` / `chat` / `execute_tool` span，来自 AgentScope 的 `OtelTracingMiddleware`），不导出日志和指标；指标仍走 `/api/metrics` 与 Prometheus 端点。endpoint 留空时完全不建 SDK、不起后台导出线程。`compose.yaml` 里的 `otel-collector` 仍在 `future` profile 下且没有配 exporter，要实际接收 trace 需要自己补 collector 配置或指向外部 collector。

令牌校验规则（`ApiTokenProperties`）：只允许非空 + ASCII；**代码不强制最小长度**，README/`.env.example` 建议至少 32 个 ASCII 字符（如 `openssl rand -hex 32`）。非 ASCII 会启动失败，原因是 Tomcat 按 ISO-8859-1 解码请求头，非 ASCII 令牌永远匹配不上。`toString()` 已脱敏，日志里不会打印令牌。

## 3. `application.yml` 逐项

| key | 值 | 含义 |
| --- | --- | --- |
| `spring.lifecycle.timeout-per-shutdown-phase` | `30s` | 优雅停机单阶段超时 |
| `spring.datasource.url` | `jdbc:sqlite:file:${APP_DATA_DIR:./var}/app.db?busy_timeout=5000` | 单文件 SQLite，5 秒忙等 |
| `spring.datasource.driver-class-name` | `org.sqlite.JDBC` | 驱动 |
| `spring.datasource.hikari.maximum-pool-size` | `4` | 连接池上限；SQLite 单写者，池子不宜大 |
| `spring.flyway.locations` | `classpath:db/migration/sqlite` | 迁移脚本目录（在 `data` 模块资源里） |
| `spring.web.resources.static-locations` | `file:./frontend/` | 以进程工作目录为基准托管静态页 |
| `app.api-token` | `${APP_API_TOKEN:}` | 令牌；空白 → 启动失败 |
| `server.shutdown` | `graceful` | SIGTERM 时等待在途请求（含 SSE） |
| `server.port` | 未设置 → 8080 | 应用端口 |
| `management.server.port` | `8081` | 独立管理端口 |
| `management.server.address` | `127.0.0.1` | 只听回环，容器外不可达 |
| `management.endpoints.web.exposure.include` | `health` | 只暴露健康检查 |
| `mybatis-plus.configuration.map-underscore-to-camel-case` | `true` | 列名 → 字段名 |
| `mybatis-plus.global-config.db-config.id-type` | `auto` | 自增主键 |

上游地址**不在 `application.yml` 里**：每个适配器用 `@Value("${app.datacenter-url:<默认东财地址>}")` 自带默认值（如 `BuybackAdapter.java:57`、`StockMasterSync.java:45`、`CompanyProfileAdapter.java:46`），改指其他主机时用同名环境变量/`--app.xxx-url=` 覆盖，不需要改代码。

## 4. 本地开发

仓库根目录执行（工作目录必须能找到 `./frontend` 和 `./var`）：

```bash
export JAVA_HOME=/path/to/jdk-21
export APP_API_TOKEN="$(openssl rand -hex 32)"
export DEEPSEEK_API_KEY="sk-..."        # 不填也能启动，只是问答会报“助手不可用”
export BAIDU_API_KEY="..."              # 可选：web_search 优先走百度，超限回落 DeepSeek
export APP_DATA_DIR=./var

# 多模块 reactor 下 -am 不能和 spring-boot:run 同一条命令，先 install 一次
./mvnw -pl app -am install -DskipTests
./mvnw -pl app spring-boot:run
```

**已实测（2026-10-31）**：`app/pom.xml` 的 `spring-boot-maven-plugin.workingDirectory=${project.basedir}/..` 生效，`spring-boot:run` 的工作目录是**仓库根**（不是 `app/`）。启动日志里 Flyway 打开的是 `./var/app.db`，`app/var/app.db` 没有被创建；`/` 返回 200（仓库根 `frontend/`），`/health` 返回 `ok`。因此 README 里「工作目录是 `app/`」以及 `APP_SKILLS_DIR=../skills`、`static-locations=file:../frontend/` 的写法已经过时，默认值即可。

本机若 8081 被占用（管理端口），启动会失败在 `Web server failed to start. Port 8081 was already in use`；排查用 `netstat -ano | findstr 8081`，或临时 `--server.port=18080 --management.server.port=18081`。

另一种方式（推荐用于验收）：

```bash
./mvnw -pl app -am package -DskipTests
java -jar app/target/app-0.1.0-SNAPSHOT.jar
```

浏览器打开 <http://127.0.0.1:8080/>，首次请求 API 时页面弹出令牌输入框，令牌存 `localStorage["ir_token"]`。

## 5. 构建与测试命令

| 目的 | 命令 |
| --- | --- |
| 全量测试 + 打包校验 | `./mvnw verify`（需 `export JAVA_HOME=<jdk21>`） |
| 只跑测试 | `./mvnw -q test` |
| 格式化检查 | `./mvnw -q spotless:check` |
| 单模块（含依赖） | `./mvnw -pl data -am test` —— **必须带 `-am`**，裸 `-pl data` 会用本地仓库里的旧 `core` jar |
| 离线构建 | `./mvnw -o ...`（本机已缓存全部依赖） |

CI 口径即 `mvn verify` + `spotless:check`（设计文档 §测试策略）。仓库当前**没有 CI 配置文件**（无 `.github/workflows`）。

## 6. Docker 与 Compose

`Dockerfile`：两阶段。

1. 构建阶段 `maven:3.9-eclipse-temurin-21`，复制全部 pom 与模块，`mvn -B -DskipTests package`。
2. 运行阶段 `eclipse-temurin:21-jre`，装 `curl`，创建 UID/GID 10001 的 `app` 用户，`WORKDIR /app`，复制 jar → `/app/app.jar`、`frontend/`、`skills/`，`USER app`，`EXPOSE 8080`。
   `HEALTHCHECK`：`curl -f http://127.0.0.1:8081/actuator/health`，30s 间隔、60s 启动宽限、3 次重试。
   入口：`java -jar /app/app.jar`。

`compose.yaml`：

| 项 | 值 | 说明 |
| --- | --- | --- |
| `app.build` / `image` | `.` / `investment-research:latest` | 本地构建 |
| `ports` | `80:8080` | **只发布应用端口**；8081 不发布 |
| `environment` | `APP_API_TOKEN`（未设置则 compose 直接报错）、`DEEPSEEK_API_KEY`、`BAIDU_API_KEY`、`XQ_TOKEN`、`APP_DATA_DIR=/app/var`、`JAVA_TOOL_OPTIONS`、`OTEL_*` | 从 `.env` 注入 |
| `volumes` | `./var:/app/var`、`./skills:/app/skills:ro` | 数据持久化；skills 可被宿主机整体覆盖 |
| `stop_grace_period` | `35s` | 比 30s 优雅停机多 5 秒余量 |
| `profiles: future` | `redis:7-alpine`、`rabbitmq:3-management-alpine`、`otel/opentelemetry-collector-contrib` | **默认不启动**，应用也不依赖 |

启动：

```bash
cp .env.example .env          # 填 APP_API_TOKEN、DEEPSEEK_API_KEY
mkdir -p var && sudo chown -R 10001:10001 var   # 容器以 UID 10001 写 SQLite
docker compose up -d --build
curl -s http://127.0.0.1/health   # ok
```

## 7. 运维脚本

`scripts/smoke.sh`：四项断言，任一失败非零退出。

1. `/health` == `ok`
2. 无令牌访问 `/api/conversations` == 401
3. 带 `APP_API_TOKEN` 返回 JSON 数组
4. `/` 返回包含 `<html`

不调用模型，不需要 `DEEPSEEK_API_KEY`。变量：`APP_API_TOKEN`（必需）、`BASE_URL`（默认 `http://127.0.0.1:8080`）。

`scripts/backup.sh`：`sqlite3 .backup` 生成一致性快照。

- 输入：`$DATA_DIR/app.db`（`DATA_DIR` 默认 `./var`），不存在则退出 1。
- 输出：`$DATA_DIR/backups/app-YYYYMMDD.db`，同名覆盖。
- 保留最近 7 份，按文件名日期删除更早的。
- `umask 077`；容器部署后 `var/` 属 UID 10001，cron 需以 root 或 10001 运行。
- 恢复：停应用 → 把备份复制回 `$DATA_DIR/app.db`。

## 8. 安全相关配置（只列项，不含值）

- 令牌只保护 `/api/**`；白名单路径见 `SecurityConfig.PUBLIC_PATHS`：`/health`、`/`、`/index.html`、`/app.js`、`/styles.css`、`/boot.js`、`/assets/**`、`/favicon.ico`、`/error`、`/actuator/health`。
- 无状态、CSRF 关闭、ASYNC dispatch `permitAll`（SSE 需要）。
- 401 响应：`{"error":"unauthorized"}`，UTF-8。
- 管理端口只暴露 `health`，且只听 `127.0.0.1`。
- 密钥不入库、不写日志：`ApiTokenProperties.toString()` 脱敏；上游 URL 不含密钥。
- 审计日志：`AuditLogFilter` 为每个 `/api/**` 请求写一行 JSON 到 `${APP_DATA_DIR:-./var}/logs/audit.log`，字段为 `ts/method/path/status/duration_ms/ip/auth/async`（401 时 `auth=missing|invalid`）。按 10MB/天滚动、保留 30 天；**不记录请求体、查询串和 `Authorization` 头**。容器部署时随 `var/` 一起挂载。

## 9. 已知配置问题与风险

| 问题 | 证据 | 影响 |
| --- | --- | --- |
| `XQ_TOKEN` 只出现在 `.env.example`/`compose.yaml`/README，没有代码读取 | 全仓 grep 只有这三处 | 雪球相关工具（若有）拿不到 token；当前 33 个工具里没有直接依赖雪球的实现，属于**部分实现/待确认** |
| `micrometer-registry-prometheus` 已引入但只暴露 `health` | `app/pom.xml`、`application.yml` | 指标端点不可用；Prometheus 采集在阶段 4 才规划 |
| `.env.example` 说令牌「至少 32 字符」，代码只校验非空 + ASCII | `ApiTokenProperties.java` | 弱令牌能启动，安全靠文档约束 |
| 静态文件白名单逐个硬编码 | `SecurityConfig.PUBLIC_PATHS` | 新增顶层静态文件要同时改安全配置，否则被 401 |
| `APP_MEMORY_IMPORT` 只在「档案非空」时跳过，非幂等（导入中断后不会重试） | `MemoryImportRunner.java` | 部分导入的库需要手工清空后重启 |
| 明文 HTTP + localStorage 令牌 | `README.md` 部署风险段 | 公网部署必须前置 TLS 反代，否则令牌可被复用 |
| `frontend/README.md` 仍写「启动：python -m assistant.api，8502 端口」、运行日志在 `data/run_logs/` | `frontend/README.md:23,39` | 与现状（Spring Boot、`var/runs`）不符，交接时以本文件为准 |
