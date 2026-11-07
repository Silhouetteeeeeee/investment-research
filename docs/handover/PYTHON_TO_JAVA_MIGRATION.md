# Python → Java 迁移映射

基准：`D:\Programming\PythonProject`（只读，不改）。证据范围：两仓源码静态比对 + 提交历史；**未逐条跑 Python 测试**，因此「已实现」表示「有对应实现且已装配」，不表示行为已逐字段验证（金标准覆盖范围见 `TESTING_AND_QUALITY.md`）。

状态口径：**已实现** = 对应 Java 类存在且被装配；**部分实现** = 只覆盖一部分行为或口径有已知差异；**未迁移** = 有意不迁或无对应物；**待确认** = 源码中找不到足够证据。

## 1. 总体映射

| Python 文件/模块 | 原职责 | Java 对应 | 迁移方式 | 状态 | 证据 |
| --- | --- | --- | --- | --- | --- |
| `assistant/api.py`（SSE/路由） | 提问流式问答、8 类 SSE 事件 | `app.api.AskController` + `core.service.AskService` + `app.api.SseWriter` | `SseEmitter` + 虚拟线程执行器 | 已实现 | `AskController.java:41`、`AskService.java:60` |
| `assistant/api.py`（会话/证据/运行接口） | 历史、证据、运行详情、删除 | `app.api.ConversationController` | 直接端口调用 | 已实现 | `ConversationController.java:27-155` |
| `assistant/api.py`（研究档案接口） | `/api/research/companies*` | `app.api.ResearchController` | 直接端口调用 | 已实现 | `ResearchController.java:23-56` |
| `api.py` 的 `route_question` | 公司/大盘识别 | `core.service.QuestionRouter` | 重写（六位代码 > 名称 > 大盘） | 已实现 | `QuestionRouter.java:46-64` |
| `api.py` 的 `agent_for`（按会话缓存 agent、回放历史） | 会话恢复 | AgentScope `JsonFileAgentStateStore`（`APP_DATA_DIR/sessions`） | 换框架，状态存文件 | 已实现（机制不同） | `AgentEngine.java:111,365-386` |
| `assistant/agent.py` LLM 循环 | ReAct 循环、10 轮、temperature 0.3 | `core.agent.AgentEngine` + `AgentFactory` + `AgentProperties` | 用 `HarnessAgent` 重建 | 已实现（框架不同） | `AgentEngine.java:355-394`、`AgentProperties.java:8-15` |
| `agent.py` 上下文压缩（55k 软/80k 硬、摘要、裁剪） | 上下文管理 | `CompactionConfig(triggerTokens=80_000)` + `ContextMeter` | 只用框架硬阈值 | **部分实现** | `AgentEngine.java:373`、`ContextMeter.java:22` |
| `agent.py` 会话内工具缓存/旧结果换 stub | 省重复请求 | `core.agent.ToolCallCache` + `AgentEngine.CachedTool` | 重写 | 已实现 | `ToolCallCache.java`、`AgentEngine.java:97-105` |
| `agent.py` 运行记录 | 时间线 JSON | `core.run.RunRecorder` + `RunRecord`/`RunSummary` | 重写，字段部分缺失 | **部分实现** | `RunRecorder.java:41-58` |
| `assistant/runlog.py` | 运行文件读写、id 校验、保留 200 份 | `core.run.FileRunStore` | 重写（原子写、按 mtime 剪枝） | 已实现 | `FileRunStore.java` |
| `assistant/store.py` | 会话/消息 SQLite | `ConversationRepositoryImpl` + `ConversationMapper`/`MessageMapper` + `V1` | MyBatis-Plus + JdbcTemplate | 已实现 | `ConversationRepositoryImpl.java`、`V1__conversation.sql` |
| `assistant/tools.py` 33 个 schema | 工具 schema 与分发 | `ToolSchemas`（导出 JSON）+ `ToolRegistry` + 32 个 `*Tool` 类（另加 Java 新增 `WebSearchTool`） | schema 由 Python 生成，Java 只实现执行；`web_search` 为 Java 独有，schema 手工追加 | 已实现（33 注册 + 框架 `load_skill_through_path`） | `core/src/main/resources/tool-schemas.json`、`AssistantConfig.java:98-166` |
| `assistant/tools.py` 结果整形 | `ToolResult` 结构 | `core.domain.ToolResult` + 各 Tool 类 | 重写，JSON 形状对拍 | 已实现 | `ToolResult.java`、`ToolResultTest` |
| `assistant/skills.py`（15 个 Python 常量） | skill 判据正文 | `skills/<name>/SKILL.md`（15 个）+ `FileSystemSkillRepository` | 改为 Markdown 目录 | 已实现（正文逐字一致，`business_model` 无快照用例） | `SkillContentTest`、`README.md:97-98` |
| `assistant/prompts.py` | 系统提示词 | `core/src/main/resources/prompts/system.md` | 抄写并改工具名 | 已实现 | `system.md`、`AgentEngine.java:72` |
| `assistant/research_memory.py` 规则 | 校验/编号/枚举 | `core.research.MemoryRules` | 重写 | 已实现 | `MemoryRules.java:17-35` |
| `assistant/research_memory.py` 存储 | 6 表档案 | `data.adapter.memory.ResearchMemoryStore` + `V10` | JdbcTemplate | 已实现 | `ResearchMemoryStore.java`、`V10__research_memory.sql` |
| `assistant/company_profile.py` | F10 公司概况 | `data.adapter.f10.CompanyProfileAdapter` | 端口适配 | **部分实现**（缓存 TTL 与 Python 不同，见 §3） | `CompanyProfileAdapter.java:27-29` |
| `assistant/valuation_math.py` | 估值计算（Decimal） | `core.valuation.ValuationMath` | 重写（`BigDecimal`/`MathContext`） | 已实现 | `ValuationMath.java` |
| `assistant/citations.py` | `[[id]]` 校验 | `core.citation.CitationValidator` | 重写 | 已实现 | `CitationValidator.java` |
| `assistant/audit.py` | 回答审计（引用 + skill 路由硬检查） | 只迁了引用校验 | 拆分 | **部分实现 / 待确认** | 无 `audit_answer`/`loaded_skills` 对应物 |
| `assistant/models.py` | `Source`/`ToolResult` | `core.domain.Source` / `ToolResult` | record | 已实现 | `core/domain/` |
| `assistant/render.py` / `webapp.py` / `server.py` | Streamlit UI 与进程管理 | 无 | 不迁（非目标） | 未迁移 | — |
| `assistant/selection.py` / `market_widget/*` | Qt 桌面端 | 无 | 不迁（非目标） | 未迁移 | — |
| `assistant/cli.py` | 命令行 | 无 | 不迁 | 未迁移 | — |
| `assistant/env.py` | `.env` 读取 | Spring 环境变量 + `.env.example` | 平台替代 | 已实现（机制不同） | `.env.example` |
| `research/market_state.py` | 大盘分类 | `core.research.MarketState` + `data.adapter.market.MarketAdapter` | 纯函数 + 适配器 | 已实现 | `MarketState.java`、`MarketAdapter.java` |
| `research/news_events.py` | 新闻事件评分 | `core.research.NewsEvents` + `NewsAdapter` | 拆分 | 已实现 | `NewsEvents.java`、`NewsAdapter.java` |
| `research/sector_rotation.py` | 板块轮动 | `core.research.SectorRotation` + `SectorAdapter` | 拆分 | 已实现 | `SectorRotation.java`、`SectorAdapter.java` |
| `research/screening.py` | 量化筛选 | `core.research.Screening` + `ScreenAdapter` | 拆分 | 已实现 | `Screening.java`、`ScreenAdapter.java` |
| `research/strategy.py` | 条件式仓位方案 | `core.research.Strategy` + `StrategyAdapter` | 拆分 | 已实现 | `Strategy.java`、`StrategyAdapter.java` |
| `research/review.py` | 存档/复盘 | `core.research.Review` + `ReviewAdapter` + `DailyReportStore` | 拆分 | 已实现 | `Review.java`、`V11__daily_reports.sql` |
| `research/store.py` | `daily_reports` 表 | `data.persistence.DailyReportStore` + `V11` | 重写 | 已实现 | `DailyReportStore.java` |
| `stock/db_service.py` | 公司查询/归一化/自选股 | `data.adapter.StockDirectoryAdapter` | 端口；自选股不迁 | **部分实现** | `StockDirectoryAdapter.java:16-81` |
| `stock/market.py` | 大盘概览 | `MarketAdapter` | 端口 | 已实现 | `MarketAdapter.java:23` |
| `stock/analyst_views.py` | 卖方研报观点 | `AnalystViewAdapter` | 端口 | 已实现 | `AnalystViewAdapter.java:23` |
| `stock/announcements.py` | 公告 | `AnnouncementAdapter` | 端口 | 已实现 | `AnnouncementAdapter.java:28` |
| `stock/buybacks.py` | 回购 | `BuybackAdapter` | 端口 | 已实现 | — |
| `stock/dividends.py` | 分红 | `DividendAdapter` | 端口 | 已实现 | — |
| `stock/forecasts.py` | 业绩预告 | `ForecastAdapter` | 端口 | 已实现 | — |
| `stock/insider_trades.py` | 高管增减持 | `InsiderTradeAdapter` | 端口 | 已实现 | — |
| `stock/lockups.py` | 限售解禁 | `LockupAdapter` | 端口 | 已实现 | — |
| `stock/management.py` | 管理层 | `ManagementAdapter` | 端口 | 已实现 | — |
| `stock/peers.py` | 同业 | `PeerAdapter` | 端口 | 已实现 | — |
| `stock/pledges.py` | 质押 | `PledgeAdapter` | 端口 | 已实现 | — |
| `stock/research.py` | 机构调研/调研纪要 | `ResearchAdapter`、`ResearchNotesAdapter` | 端口 | 已实现 | — |
| `stock/segments.py` | 主营构成 | `f10.SegmentAdapter` | 端口 | 已实现 | — |
| `stock/shareholders.py` | 股东 | `ShareholderAdapter` | 端口 | 已实现 | — |
| `stock/valuation.py` | 估值数据 | `ValuationAdapter` | 端口 | 已实现 | — |
| `stock/finance_*`（finance_fields/metrics/history/snapshot） | 财报三表与指标 | `FinanceAdapter`、`FinanceSupport`、`FinanceSnapshot`、`FinanceStatements`、`FinanceMetrics`、`FinancialHistoryAdapter` | 端口/拆分 | 已实现 | `FinanceAdapter.java` |
| `stock/disclosure_service.py` | 披露状态刷新 | 只有 `FinDisclosureMapper`（`V8` 表存在，但无写入者） | 未接 | **部分实现 / 待确认** | `FinanceAdapter.java:46` 注释 |
| `stock/finance_scheduler.py` | 定时刷新财报 | 无 | — | **未迁移** | 无对应物 |
| `stock/briefing.py` | 桌面端简报 | 无 | — | 未迁移 | — |
| `stock/utils.py` | 前缀/新浪/雪球/交易日历 | 部分拆成 `EastmoneySupport`、`Sina*Source`、`MarketSupport` | 部分 | **部分实现**（雪球未接，`XQ_TOKEN` 无代码读取） | — |
| `common/stock.py` | `Stock`/`Quote` | `core.domain.StockRef`、`StockEntity` | 简化 | **部分实现** | — |
| `common/db_utils.py` | SQLite 助手 | MyBatis-Plus 配置 + `ResearchCacheStore` | 平台替代 | 已实现（机制不同） | — |
| `main.py` / `init.py` | akshare 实验脚本 | 无 | 不迁 | 未迁移 | — |
| `frontend/*` | 原生页面 | `frontend/*` | 拷贝（`boot.js`/`styles.css` 相同；`app.js`/`index.html` 有改动） | **部分实现 / 待确认**（功能等价未验证） | 文件比对 |
| `test/`（80+ 文件） | Python 测试 | `core/data/app/src/test` | 重写 + 金标准对拍 | 已实现（覆盖面不同） | `golden/`、`ToolSchemaParityTest` |

## 2. 数据库映射

Python 有四个 SQLite：

| Python 库 | 表 | Java 落点 |
| --- | --- | --- |
| `data/stock.db` | 26 张（含 akshare 转储 `lrb`/`zcfzb`/`xjllb`） | 合并进 `var/app.db` 的同名表（V2–V9）；**`lrb`/`zcfzb`/`xjllb` 不迁** |
| `data/assistant.db` | `conversation`、`message` | `var/app.db`（V1），字段基本一致，Java 多 `usage_json` |
| `data/research_memory.db` | 6 张 `memory_*` / `company_memory` | `var/app.db`（V10），可用 `APP_MEMORY_IMPORT` 导入 |
| `data/research.db` | `daily_reports` | `var/app.db`（V11） |

细节见 `DATA_AND_DATABASE.md`。

## 3. 已知口径差异（迁移时有意或无意）

| 差异 | Python | Java | 判断 |
| --- | --- | --- | --- |
| 上下文压缩 | 55k 软阈值摘要 + 80k 硬阈值裁剪 | 只有 `triggerTokens`（聊天 80_000、交易 120_000） | 有意简化，README 已记 |
| 运行记录字段 | 含 `stream`、`message_count`、`input_chars`、`system_share`、`tool_share`、`system_prompt_hash`、`tools_hash` | 缺失；`prompts`/`skills`/`calls` 多为空数组 | 已知差异 |
| token 用量 | 有 usage 用真实值，否则估算 | 同思路，`usage_source` 标注 | 一致 |
| 公司概况缓存 TTL | 按来源规则 | 30 天（`CompanyProfileAdapter`） | 待确认是否有意 |
| 雪球数据 | `stock/utils.py` 有雪球客户端 | 无代码读取 `XQ_TOKEN` | 未迁移 |
| 自选股 / watchlist | `db_service.py` 有 | 无 | 非目标 |
| skill 路由审计 | `audit.py` 检查是否加载 skill | 只有引用校验 | 待确认是否有意省略 |
| 缺模型 key 的行为 | 可能仍建会话 | 不建会话、不落历史，只回 error 事件 | 有意（README 已记） |
| 工具数 | 33 个 schema（含 `load_skill`） | 33 个 Java 工具 + 框架 `load_skill_through_path`；其中 `web_search` 为 Java 新增 | Python 的 32 个等价，另多一个联网搜索 |

## 4. Java 独有（Python 无对应物）

- Spring Boot / Spring Security 令牌鉴权（`SecurityConfig`、`TokenAuthFilter`、`ApiTokenProperties`）。
- Flyway 迁移与 MyBatis-Plus 持久化。
- AgentScope `HarnessAgent`、`CompanyPromptMiddleware`、框架级 skill 加载。
- 三模块拆分与 ArchUnit 边界测试。
- 金标准/录制基础设施：`tools/golden/gen_golden.py`、`tools/fixtures/record.py`。
- `MemoryImportRunner`（启动时导入旧档案）。
- Dockerfile / compose / `scripts/backup.sh` / `scripts/smoke.sh`、8081 管理端口。
- `conversation.usage_json` 与 `updateUsage`（Python 只临时计算）。

## 5. 尚未确认的点

1. 是否要补 Python 的软阈值摘要与 `audit.py` 的 skill 路由审计。
2. `stock/disclosure_service.py`、`finance_scheduler.py` 是延期还是放弃。
3. `frontend/app.js` 与 Python 版的功能等价性（只做了文件级比对）。
4. `CompanyProfileAdapter` 30 天 TTL 是否为有意取舍。

这些点在没有作者确认前，按「部分实现/待确认」处理，不要当成缺陷直接修。
