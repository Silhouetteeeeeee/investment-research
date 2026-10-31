# 项目概览

## 1. 这是什么

`investment-research` 是原 Python 项目 `D:\Programming\PythonProject` 中**「页面上的财报分析助手」**的 Java 重写版。它把「上游取数 → 工具 → Agent 循环 → 引用校验 → 运行记录 → 会话持久化 → HTTP/SSE → 页面」整条链路搬到 Java 上，目标是可按生产标准部署（Docker Compose、健康检查、优雅停机）、可测试（金标准对照 Python 输出）、可演进。

来源：`D:/Programming/PythonProject/docs/superpowers/specs/2026-10-30-java-migration-design.md`（下称「迁移设计」）。

Python 仓库**保持不动**，作为金标准对照基准（含 `data/stock.db` 等冻结数据）。

## 2. 范围

**在范围内**

- 32 个研究工具（注册顺序与 Python `assistant.tools.schemas()` 一致，见 `README.md:85-95`）。
- 15 个 skill（`skills/<name>/SKILL.md`）。
- AgentScope-Java `HarnessAgent` 装配、文件系统 skill、上下文压缩、会话状态。
- `[[source-id]]` 引用协议与 `issues` 校验。
- 会话/消息持久化、运行记录（`var/runs/<run_id>.json`）、证据回取。
- `/api/**` + SSE 契约，前端 `frontend/` 原样复用（仅加令牌输入）。
- 研究档案（记忆）读写与旧库导入。

**不在范围内（迁移设计「非目标」）**

- Qt 行情窗（Python `market_widget/`）、Streamlit 页面（Python `assistant/webapp.py`）。
- 多用户/注册/权限体系（只有一个静态令牌）。
- 历史对话与旧 `stock.db` 缓存（只导入研究档案）。
- RAG / 向量库、Redis / RabbitMQ 实际读写（只留 profile 配置）。
- K8s、多副本、灰度发布、实盘交易。

## 3. 当前状态（以 `git log` 与迁移设计为准）

| 阶段 | 内容 | 状态 | 证据 |
| --- | --- | --- | --- |
| 阶段 0 | 骨架：三模块、Flyway、健康检查、令牌鉴权、静态页、`/api/ask` 占位 | **已实现** | 早期提交（`04b4519` 及之前）；`TokenAuthTest`、`HealthControllerTest` |
| 阶段 1 | 第一问纵向切片：概况/财报/主营/同业/公告/估值 6 类适配器 + 8 工具 + `business_model` skill + SSE + 运行详情 | **已实现** | 提交 `fe74152`..`5e3f764`；`AskFlowTest` |
| 阶段 2 | 公司研究其余技能：管理层/股东/分红/内部人/回购/解禁/质押/预告/调研/研报/分析师/历史财务/估值计算，共 8 个 skill | **已实现** | 提交 `4418da2`..`ac83143`；核心测试 `core 117`、`data 118` |
| 阶段 3 | 大盘、新闻事件、板块轮动、选股、策略、复盘、研究档案 | **已实现** | 提交 `43b0d17`..`12b6585`；`AskFlowTest` 大盘用例、`ResearchMemoryStoreTest` |
| 阶段 4 | 硬化：全量对照、慢查询与并发、日志/指标/追踪、备份恢复演练、MySQL profile（Testcontainers）、Redis/RabbitMQ profile 可启动 | **计划实现** | 无代码证据；`docs/` 中无阶段 4 计划 |
| 阶段 5 | 切换：云服务器部署、Python 版停服、保留金标准夹具 | **计划实现** | 未开始 |

当前分支 `main`，HEAD 见 `git log`。`./mvnw verify` 现状：core 127 / data 119 / app 56 全绿。

## 4. 技术栈

| 层 | 选择 | 版本 |
| --- | --- | --- |
| 语言/构建 | Java + Maven 多模块 | Java 21，Maven Wrapper |
| Web | Spring MVC + `SseEmitter`（不用 WebFlux） | Spring Boot 3.5.16 |
| Agent | AgentScope-Java `HarnessAgent` | 2.0.4（锁版本） |
| 模型 | `agentscope-extensions-model-openai`，默认 `deepseek:deepseek-v4-flash` | — |
| 持久化 | MyBatis-Plus（简单 CRUD）+ `JdbcTemplate`（动态 SQL/upsert/事务） | 3.5.18 |
| 迁移 | Flyway（SQLite 方言目录） | — |
| 数据库 | SQLite 单文件 | `sqlite-jdbc` 3.54.1.0 |
| 缓存 | 进程内 `ConcurrentHashMap` 短 TTL（caffeine 3.3.1 在依赖里，但主代码未使用） |
| HTML 解析 | jsoup | 1.17.2 |
| 测试 | JUnit 5、AssertJ、WireMock、ArchUnit | 3.13.2 / 1.5.1 |
| 前端 | 原生 JS + Vue 3 全局构建（本地内置，无构建工具） | Vue 3.5.44 / marked 18.1.1 / DOMPurify 3.4.17 |

## 5. 仓库形态

```text
investment-research/
├── pom.xml              父 POM：Java 21、依赖版本、Spotless、Surefire
├── core/                ~107 个主类 / 8.3k 行：领域、端口、Agent 装配、工具、引用、运行记录、估值
├── data/                ~87 个主类 / 17.7k 行：上游适配器、持久化、Flyway、定时同步
├── app/                 ~12 个主类 / 1.1k 行：Spring Boot 入口、Controller、SSE、安全、配置
├── skills/              15 个 <name>/SKILL.md
├── frontend/            静态页（app.js / index.html / styles.css / boot.js / assets）
├── tools/               fixtures/record.py（录上游响应）、golden/gen_golden.py（从 Python 生成金标准）
├── scripts/             backup.sh、smoke.sh
├── compose.yaml         app + 可选 future profile
├── Dockerfile           多阶段构建
└── docs/handover/       本文档集
```

依赖方向：`app → core`、`data → core`、`app → data`（Spring 装配点）。`core` 不依赖 `data`，也不依赖 Web/MyBatis/JDBC；`io.agentscope..` 只允许出现在 `core/agent`。这些由 ArchUnit `ModuleBoundaryTest` 强制。

## 6. 一次问答能做什么（能力摘要）

- 用户提问 → `QuestionRouter` 识别公司（代码/名称/别名）或大盘（`MARKET`）。
- 未识别公司 → 只回一条 warning，不建会话。
- 识别后建会话、落用户消息、通过 `HarnessAgent` 流式跑，模型按系统提示先 `load_skill_through_path` 再调工具。
- 工具结果统一 `ToolResult{ok, company, as_of, sources, data, error, cached}`；来源 id 进入本次运行的引用表。
- 回答中的 `[[source-id]]` 被 `CitationValidator` 校验，未知 id 记入 `issues`（页面只显示前 2 条）。
- 全程通过 SSE 发 `state`/`text`/`discard`/`tool`/`meter`/`done`/`error`/`warning`。
- 回答落 `message` 表（含 `raw`），运行原文写 `var/runs/<run_id>.json`，详情页可回放时间线。

## 7. 已知差异（详见 `TODO_AND_KNOWN_ISSUES.md`）

- 上下文压缩只配置了 `triggerTokens(80_000)`，未移植 Python 的完整裁剪策略。
- 运行记录缺 `stream`、`message_count`、`system_prompt_hash`、`tools_hash` 等字段，详情页部分信息为空。
- token 用量多数按估算（`usage_source: "estimate"`），不是计费依据。
- 缺 `DEEPSEEK_API_KEY` 时不建会话、不落历史。
- `XQ_TOKEN` 无代码读取（雪球路径未接入）。
- 金标准夹具覆盖面不完整（部分公司缺表数据）。

## 8. 文档索引

| 文档 | 回答什么问题 |
| --- | --- |
| `ARCHITECTURE.md` | 模块怎么分层、依赖方向、核心调用序列、Mermaid 图 |
| `DIRECTORY_GUIDE.md` | 每个目录/重要文件是干什么的 |
| `MODULE_DETAILS.md` | 每个包/类的职责、关键方法与常量 |
| `DATA_AND_DATABASE.md` | 表结构、迁移、缓存与读写路径 |
| `API_AND_INTERFACE.md` | HTTP 路由、SSE 事件、工具契约 |
| `PYTHON_TO_JAVA_MIGRATION.md` | Python 模块 → Java 对照表、迁移状态 |
| `CONFIGURATION_AND_DEPLOYMENT.md` | 环境变量、application.yml、Docker、脚本（不含密钥） |
| `TESTING_AND_QUALITY.md` | 测试分层、夹具、如何跑、质量门禁 |
| `TODO_AND_KNOWN_ISSUES.md` | 技术债、潜在缺陷、延期项、阶段 4/5 待办 |
| `PROJECT_KNOWLEDGE_MAP.md` | 问题驱动的代码索引（从启动到返回的每条链路） |
| `CLAUDE_HANDOVER.md` | 给下一位 Claude 的操作手册与禁令 |
