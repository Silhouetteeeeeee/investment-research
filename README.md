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
./mvnw -pl app spring-boot:run
```

或打包成可执行 jar 后运行：

```bash
./mvnw -pl app -am package -DskipTests
java -jar app/target/app-0.1.0-SNAPSHOT.jar
```

默认端口 `8080`，浏览器打开 <http://127.0.0.1:8080/>。`/health` 免认证；
页面首次请求 API 时弹出令牌输入框，令牌保存在浏览器 `localStorage["ir_token"]`，
之后的请求自动带 `Authorization: Bearer <token>`。

## 测试

```bash
./mvnw -q test
./mvnw -q spotless:check
```

## 容器部署（Docker Compose）

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

快照是普通 SQLite 文件，恢复时停掉应用、把它复制回 `$DATA_DIR/app.db` 即可。
