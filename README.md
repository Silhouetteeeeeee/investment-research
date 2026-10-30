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
