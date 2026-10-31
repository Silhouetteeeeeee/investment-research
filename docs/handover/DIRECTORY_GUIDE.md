# 目录指南

根目录 `D:\Programming\investment-research`。规模：主代码 206 个 Java 文件（core 107 / data 87 / app 12），测试 17+30+14 个文件。统计口径为 `find ... -name '*.java' | wc -l`。

## 1. 顶层

| 路径 | 作用 | 备注 |
| --- | --- | --- |
| `pom.xml` | 父 POM | Java 21、依赖版本、Spotless、Surefire、模块 `core`/`data`/`app` |
| `mvnw`、`mvnw.cmd`、`.mvn/` | Maven Wrapper | 本机离线构建用 `./mvnw -o` |
| `core/` | 领域与编排 | 不依赖 Web/DB/框架实现 |
| `data/` | 上游适配器 + 持久化 + 迁移 | 实现 `core.port` |
| `app/` | Spring Boot 入口 | Controller、SSE、安全、Bean 装配 |
| `frontend/` | 静态页面 | 由 `app` 直接托管，无构建步骤 |
| `skills/` | 15 个 `<name>/SKILL.md` | AgentScope `FileSystemSkillRepository` 扫描 |
| `tools/fixtures/record.py` | 录制真实上游响应 → WireMock 映射 | 需联网；输出 `data/src/test/resources/wiremock` |
| `tools/golden/gen_golden.py` | 从 Python 仓生成金标准与工具 schema | 输出 `core/src/test/resources/golden/*`、`core/src/main/resources/tool-schemas.json` |
| `scripts/smoke.sh` | 部署冒烟（4 项断言） | 需要 `APP_API_TOKEN` |
| `scripts/backup.sh` | SQLite 一致性备份，保留 7 份 | `DATA_DIR` 可覆盖 |
| `Dockerfile` | 两阶段构建、非 root、HEALTHCHECK 8081 | — |
| `compose.yaml` | `app` 服务 + `future` profile | 只发布 80:8080 |
| `.env.example` | 三个环境变量占位 | 无真实值 |
| `.gitignore` | 忽略 `target/`、`.env`、`*.db`、`var/`、`.agentscope/` | 例外：`core/src/test/resources/fixtures/*.db` |
| `README.md` | 面向使用者的运行/部署说明 | 与本文档集互补 |
| `var/` | 本地运行数据（git 忽略） | `app.db`、`runs/`、`sessions/`、`workspace/` |
| `docs/handover/` | 本文档集 | — |
| `.superpowers/sdd/` | 阶段 0–1 / 阶段 3 的执行台账与评审包（git 忽略） | 决策记录，只在本地 |

## 2. `core/`（107 文件，约 8.3k 行）

| 包 | 类 | 职责 |
| --- | --- | --- |
| `core/agent` | `AgentEngine`、`AgentFactory`、`AgentProperties`、`AskResult`、`AssistantEngine`、`EngineFailure`、`StreamListener`、`ToolCallCache`、`ToolEvent` | HarnessAgent 装配、一次问答的流式事件映射、工具缓存、运行记录接线 |
| `core/citation` | `CitationValidator`、`CitationCheck` | `[[source-id]]` 校验、未知 id 替换、无引用数字告警 |
| `core/domain` | `Conversation`、`Source`、`StockRef`、`StoredMessage`、`ToolResult`、`ToolSchema`、`TraceStep` | 纯数据 record |
| `core/port` | 29 个接口 | 公司目录、会话仓库、运行存储、各类数据端口 |
| `core/research` | `MarketState`、`MemoryRules`、`MemoryException`、`NewsEvents`、`Review`、`Screening`、`SectorRotation`、`Strategy` | 与 Python 对齐的纯计算规则 |
| `core/run` | `FileRunStore`、`RunRecord`、`RunRecorder`、`RunSummary` | 运行原文生成与落盘 |
| `core/service` | `AskService`、`CompanyResolver`、`ContextMeter`、`EventSink`、`QuestionRouter`、`ResolvedCompany`、`Route` | 路由、编排、上下文占用 |
| `core/tool` | 32 个工具类 + `ToolRegistry`、`ToolSchemas`、`ResearchTool`、`CompanyTool`、`ToolArguments`、`MemoryToolSupport` | 模型可调用的工具 |
| `core/valuation` | `ValuationMath`、`ValuationInputError` | Decimal 估值计算 |

资源：`core/src/main/resources/tool-schemas.json`（33 条 schema，生成物）、`prompts/system.md`（系统提示词）。

## 3. `data/`（87 文件，约 17.7k 行）

| 包 | 类 | 职责 |
| --- | --- | --- |
| `data/adapter` | `StockDirectoryAdapter` | 公司目录，懒加载股票主表 |
| `data/adapter/eastmoney` | 18 个类 | 15 个适配器（东财 datacenter/F10/研报/估值）+ `EastmoneySupport`、`DatacenterSupport`、`ValuationSupport` |
| `data/adapter/f10` | `CompanyProfileAdapter`、`SegmentAdapter`、`F10Support` | 东财 F10 概况/主营构成 |
| `data/adapter/finance` | `FinanceAdapter`、`FinancialHistoryAdapter`、`FinanceSnapshot`、`FinanceMetrics`、`FinanceStatements`、`FinanceSupport`、`FinanceException` | 新浪财报三表 → 快照/趋势 |
| `data/adapter/market` | 19 个类 | 6 个适配器（大盘、新闻、板块、选股、策略、复盘）+ 12 个新闻/行情源 + `MarketSupport` |
| `data/adapter/memory` | `ResearchMemoryStore` | 研究档案读写与旧库导入 |
| `data/config` | `UpstreamConfig`、`MybatisPlusConfig` | RestClient 与 Mapper 扫描 |
| `data/persistence` | 31 个类 | MyBatis 实体/Mapper、`ConversationRepositoryImpl`、`ResearchCache`/`ResearchCacheStore`、`DailyReportStore` |
| `data/sync` | `StockMasterSync` | 每日 06:00 刷新股票主表 |
| `data/upstream` | `UpstreamClient`、`RestClientUpstreamClient`、`UpstreamException` | 统一 HTTP（禁代理、UA、60s、2 次、并发 4） |
| `data/src/main/resources/db/migration/sqlite` | `V1`–`V11` | Flyway 迁移 |

## 4. `app/`（12 文件，约 1.1k 行）

| 包 | 类 | 职责 |
| --- | --- | --- |
| `app` | `Application` | 启动；创建 `APP_DATA_DIR`；`@EnableScheduling` |
| `app/api` | `AskController`、`ConversationController`、`ResearchController`、`HealthController`、`SseWriter`、`ApiConfig` | HTTP/SSE |
| `app/config` | `AssistantConfig`、`MemoryImportRunner` | 全部 Bean 装配、旧档案导入 |
| `app/security` | `SecurityConfig`、`TokenAuthFilter`、`ApiTokenProperties` | 令牌鉴权 |

资源：`app/src/main/resources/application.yml`。

## 5. 测试目录

| 路径 | 内容 |
| --- | --- |
| `core/src/test/java/.../{agent,citation,domain,research,run,service,tool,valuation}` | 16 个测试类 + `ScriptedModel` 假模型 |
| `data/src/test/java/.../{adapter,eastmoney,f10,finance,market,memory,persistence,upstream}` | 28 个测试类 + `EastmoneyTestSupport`、`F10TestSupport` |
| `app/src/test/java/.../{api,e2e,security}` | 14 个测试类（含 `ModuleBoundaryTest`（ArchUnit）、`SkillContentTest`、`AssistantWiringTest`） |
| `core/src/test/resources/golden/` | 7 个场景金标准 + `tools/`（99 个工具黄金）+ `rows/`（102 个数据库行黄金） |
| `core/src/test/resources/fixtures/` | `stock-snapshot.db`（冻结行情/财报库）、`research-memory.db`（旧档案样本） |
| `core/src/test/resources/skills-python/` | 14 个 Python 原文快照（`business_model` 无快照），用于 skill 正文逐字比对 |
| `data/src/test/resources/wiremock/eastmoney` | 82 个映射（含 56 页股票列表） |
| `data/src/test/resources/wiremock/market` | 21 个映射（新浪/腾讯/同花顺/财联社/乐咕/东财/央视） |
| `data/src/test/resources/wiremock/sina` | 15 个映射（5 家公司 × 三张报表） |
| `app/src/test/resources/test-web/` | 占位静态页，用于安全与静态资源断言 |

## 6. 其他

- `frontend/app.js`（1327 行）：令牌输入、markdown + 引用渲染、会话/聊天/运行详情/档案页逻辑。
- `frontend/index.html`（557 行）：页面结构，含 `<chat-pane>` 模板。
- `frontend/boot.js`（185 行）：启动动画，Esc/空格跳过，12s 兜底。
- `frontend/styles.css`（1421 行）。
- `frontend/assets/`：本地内置 Vue 3.5.44、marked 18.1.1、DOMPurify 3.4.17、启动视频 `boot/1.mp4`。
- `frontend/README.md`：**已过时**（仍写 Streamlit 8502 / `data/run_logs/`），以本目录的文档为准。

## 7. 本机运行产物（`var/`，不提交）

| 路径 | 内容 |
| --- | --- |
| `var/app.db` | SQLite（32 张业务表 + `flyway_schema_history`/`sqlite_sequence`，共 34 张） |
| `var/runs/<run_id>.json` | 运行原文，`run_id` 形如 `20261031T111246-0da539f0`，保留最新 200 份 |
| `var/sessions/default/**` | AgentScope 会话状态 |
| `var/workspace/` | HarnessAgent 工作区 |
| `var/logs/audit.log` | `/api/**` 请求审计日志（JSON Lines），按 10MB/天滚动，保留 30 天 |
