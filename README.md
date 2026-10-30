# investment-research

投资研究助手的 Java 版：Spring Boot 3.5 / Java 21，Maven 多模块（`core` / `data` / `app`）。
前端页面在 `frontend/`，由 `app` 直接托管，不需要单独构建。

## 环境变量

启动前必须设置 `APP_API_TOKEN`，否则应用会快速失败（`APP_API_TOKEN 未配置`）。

| 变量 | 必填 | 说明 |
| --- | --- | --- |
| `APP_API_TOKEN` | 是 | 页面与 API 的访问令牌；浏览器首次请求时输入。 |
| `DEEPSEEK_API_KEY` | 运行时需要 | 模型调用密钥。只从环境读取，不要写进仓库或提交。 |
| `APP_DATA_DIR` | 否 | SQLite 和运行数据目录，默认 `./var`（相对启动目录）。 |
| `APP_SKILLS_DIR` | 否 | `skills/` 所在目录，默认 `./skills`（相对启动目录）。 |

`.env`、`*.db`、`var/` 已在 `.gitignore` 中，密钥不要提交。

```bash
export JAVA_HOME=/path/to/jdk-21
export APP_API_TOKEN="$(openssl rand -hex 32)"
export DEEPSEEK_API_KEY="sk-..."
export APP_DATA_DIR=./var
```

## 运行

在仓库根目录执行，这样 `./frontend`（静态页）和 `./var`（数据）都能被找到。

开发模式（多模块 reactor 下 `-am` 不能和 `spring-boot:run` 写在同一条命令里，先 install 一次）：

```bash
./mvnw -pl app -am install -DskipTests
APP_SKILLS_DIR=../skills ./mvnw -pl app spring-boot:run
```

`spring-boot:run` 的工作目录是 `app/`，所以 `skills` 要用 `APP_SKILLS_DIR` 指回仓库根。

或打包成可执行 jar 后运行：

```bash
./mvnw -pl app -am package -DskipTests
java -jar app/target/app-0.1.0-SNAPSHOT.jar
```

默认端口 `8080`，浏览器打开 <http://127.0.0.1:8080/>。`/health` 免认证；
页面首次请求 API 时弹出令牌输入框，令牌保存在浏览器 `localStorage["ir_token"]`，
之后的请求自动带 `Authorization: Bearer <token>`。

## 数据

股票主表在首次查询（`resolve_company` 等需要公司目录的调用）时才从东财 `clist` 拉取并落库，
启动不访问上游；之后每天 06:00 定时刷新。接口地址默认
`https://push2test.eastmoney.com/api/qt/clist/get`，可用 `app.stock-list-url`
（环境变量 `APP_STOCK_LIST_URL`）改指其他可用主机；上游不可用时解析按“找不到公司”返回，不影响启动。

## 测试

```bash
./mvnw -q test
./mvnw -q spotless:check
```

## 工具

注册顺序与 Python `assistant.tools.schemas()` 一致，共 20 个：

`resolve_company`、`get_company_profile`、`get_finance`、`get_financial_history`、
`get_segments`、`get_management`、`get_shareholders`、`get_peers`、`get_dividends`、
`get_insider_trades`、`get_buybacks`、`get_lockup_expiries`、`get_pledges`、
`get_earnings_forecasts`、`get_institutional_research`、`get_research_notes`、
`get_analyst_views`、`get_announcements`、`get_valuation`、`calc_valuation`。

`calc_valuation` 有 `check` / `scenario` / `terminal` 三种 `mode`；`skills/` 下
`business_model` 之外的七个公司研究 skill（`moat`、`operating_history`、
`financial_safety`、`growth_outlook`、`management`、`valuation`、`margin_of_safety`）
正文与 Python 逐字一致。

## 阶段 1 已知差异

迁移阶段只保证核心问答链路；以下与 Python 版仍有差距，先记录在这里：

- **大盘问题**：`/api/ask` 直接返回 `{"type":"warning","text":"暂时不支持大盘问题，请指定公司。"}`，
  不建会话、不落库、不调用模型；大盘工具在阶段 3 补齐。
- **研究档案页**：`/api/research/*` 仍是占位实现——`GET /api/research/companies` 恒返回空列表，
  `GET /api/research/companies/{code}` 恒 404；页面可打开但没有真实记忆数据，阶段 3 接通。
- **token 用量估算**：没有真实模型用量时，运行记录与页面占用都按字符估算
  （`usage_source: "estimate"`），只适合看趋势，不能当作计费依据；上游返回 usage 时才标 `"api"`。
- **缺 key 时的问题不落历史**：未设置 `DEEPSEEK_API_KEY` 时应用照常启动，`/api/ask` 返回
  `{"type":"error","text":"助手不可用：请先设置环境变量 DEEPSEEK_API_KEY"}` 且没有
  `conversation_id`。与 Python 版不同，Java 不预先建会话、不保存这条用户消息，
  所以刷新页面后该问题不在历史里。
- 上下文压缩只配置了 `triggerTokens(80_000)`，没有移植 Python 的完整压缩策略。
- 运行记录缺少 `stream`、`message_count`、`input_chars`、`system_share`、`tool_share`、
  `system_prompt_hash`、`tools_hash`；详情页因此不显示流式标记，并会出现
  “消息 0 条 · system 0%”。`prompts`、`skills`、`calls` 目前也只保留空数组。
- 系统提示词保留了 `research_memory` 以及行情、新闻、选股等工具的描述，但这些工具
  尚未注册（阶段 3 补齐），真实模型可能先尝试加载失败的工具，浪费一轮；`stock_screening`
  skill 同样延到阶段 3。公司研究的 20 个工具已全部注册。
- 金标准与快照库的覆盖面并不完整：`get_research_notes` 只有 2 家公司的用例；`601318`
  在 8 张缓存表里没有数据；`600600`/`601606` 缺回购，`600600`/`601318` 缺限售解禁，
  `601606` 缺内部人交易。换一家公司回放时可能取不到数据，这是快照缺口，不是取数错误。
- `get_valuation`、`get_announcements`、`resolve_company` 等时效性工具不进入会话缓存，
  与 Python 的 `UNCACHED_TOOLS` 一致。

## 容器部署（Docker Compose）

> **HTTP 明文风险**：本阶段只提供 HTTP，没有 TLS。部署到公网 IP 时，`APP_API_TOKEN`、
> 问题与回答、公告原文全部以明文在链路上传输，路径上的任何一跳都能读到并复用这个长期令牌。
> 若要公网使用，至少在前面加一层终止 TLS 的反代（Caddy / Nginx + 证书），或只通过
> VPN / SSH 隧道访问；不要把明文端口直接暴露在公网。

```bash
cp .env.example .env          # 填 APP_API_TOKEN 和 DEEPSEEK_API_KEY
# 容器以 UID 10001 运行；./var 由 Docker 建成 root 所有会让 SQLite 建不了库
mkdir -p var && sudo chown -R 10001:10001 var
docker compose up -d --build
curl -s http://127.0.0.1/health   # ok
```

- 只发布 `80:8080`。管理端口 `8081` 不对外发布，且 `management.server.address`
  只听回环，容器健康检查走容器内的 `http://127.0.0.1:8081/actuator/health`
  （`HEALTHCHECK` 每 30s 一次）。
- `APP_API_TOKEN` **建议至少 32 个 ASCII 字符**（如 `openssl rand -hex 32`）。非 ASCII
  令牌会被启动期直接拒绝：Tomcat 按 ISO-8859-1 解码请求头，非 ASCII 令牌永远匹配不上。
  `DEEPSEEK_API_KEY`、`XQ_TOKEN` 也只从环境读取，不要写进镜像或提交仓库。
  `.env` 缺 `APP_API_TOKEN` 时 `docker compose up` 会直接报错退出，不会静默起一个无令牌实例。
- 卷：`./var` → `/app/var`（SQLite 在 `./var/app.db`，重启后数据仍在）；
  `./skills` → `/app/skills`（只读，宿主机挂载会**整体覆盖**镜像内的 `skills/`，
  所以镜像里预留的是空目录 + `.gitkeep`）。
- `frontend/` 随镜像打包到 `/app/frontend`，镜像 `WORKDIR /app`，与应用里
  `file:./frontend/` 的相对路径一致。
- 镜像多阶段构建（`maven:3.9-eclipse-temurin-21` → `eclipse-temurin:21-jre`），
  以非 root 用户运行，`JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75`。
- 收到 `SIGTERM` 后优雅停机：`server.shutdown=graceful`，单阶段最长等待 30s
  （`spring.lifecycle.timeout-per-shutdown-phase=30s`）；compose 的
  `stop_grace_period: 35s` 给进行中的 SSE 请求留出收尾时间。
- `redis`、`rabbitmq`、`otel-collector` 只在 `--profile future` 下定义，
  本阶段不启动，应用也不依赖它们（`docker compose --profile future up -d`）。

### 部署冒烟

`scripts/smoke.sh` 检查四项并失败非零退出：`/health` 返回 `ok`、无令牌访问
`/api/conversations` 返回 401、带 `APP_API_TOKEN` 能读到 JSON 数组、`/` 返回 HTML。

```bash
docker compose up -d --build
APP_API_TOKEN="<与 .env 相同>" bash scripts/smoke.sh
# 或指定地址：BASE_URL=http://127.0.0.1:8080 APP_API_TOKEN=... bash scripts/smoke.sh
```

冒烟脚本不调用模型，因此不需要 `DEEPSEEK_API_KEY`。真实模型链路仍需要一次人工检查
（离线环境无法代跑）：浏览器打开 `/`，输入令牌，提问「东鹏饮料靠什么赚钱」，
确认回答带 `[[profile:605499]]`、`[[finance:605499:…]]`、`[[segments:605499:…]]`
引用，点开运行详情能看到模型与工具时间线，刷新后回放正常。

## 管理端口

`management.server.port=8081`，只暴露 `health`（`management.endpoints.web.exposure.include=health`）。
宿主机上可以直接 `curl http://127.0.0.1:8081/actuator/health`；compose 没有发布
该端口，容器外不可达。应用端口上不再提供 `/actuator/health`。

## 备份

`scripts/backup.sh` 用 `sqlite3 .backup` 生成一致性快照，数据目录可用 `DATA_DIR`
覆盖（默认 `./var`），方便在仓库根目录之外执行：

```bash
bash scripts/backup.sh
DATA_DIR=/srv/investment-research/var bash scripts/backup.sh
```

- 输出 `$DATA_DIR/backups/app-YYYYMMDD.db`；同一天重复执行会覆盖当天文件，不会重复生成。
- 只保留最近 7 份（按文件名日期，超出的自动删除）。
- 宿主机 cron 每天 03:00 执行：

```cron
0 3 * * * cd /srv/investment-research && DATA_DIR=./var bash scripts/backup.sh >> /var/log/investment-research-backup.log 2>&1
```

容器部署后 `./var` 归 UID 10001 所有，`var/backups` 也只有它能写：cron 必须以 root
或以 10001 身份运行，否则脚本会以 `Permission denied` 直接退出（不会留下半份备份）。

快照是普通 SQLite 文件，恢复时停掉应用、把它复制回 `$DATA_DIR/app.db` 即可。
