# 待办与已知问题

状态标签：**已确认** = 有代码/测试证据；**待确认** = 需要作者或实跑确认；**延期** = 已在阶段 3 评审中记录、决定暂不修。

## 1. 功能缺口（相对 Python 与设计文档）

| # | 问题 | 状态 | 证据 | 建议 |
| --- | --- | --- | --- | --- |
| 1 | 上下文压缩只配了 `triggerTokens(80_000)`，没有 Python 的 55k 软阈值摘要与裁剪 | 已确认（有意简化） | `AgentEngine`、`ContextMeter`；README「已知差异」 | 若要追平，需要事件映射到摘要步骤；成本高，先不动 |
| 2 | 运行记录缺 `stream`、`message_count`、`input_chars`、`system_share`、`tool_share`、`system_prompt_hash`、`tools_hash`；`prompts`/`skills`/`calls` 恒为空数组 | 已确认 | `RunRecorder`；README 已知差异 | 详情页因此显示「消息 0 条 · system 0%」，需要时补字段 |
| 3 | `fin_disclosure` 表没有任何写入者，披露触发的财报刷新不会生效 | 已确认 | `FinanceAdapter.java:46` 注释 | 要么接 `stock/disclosure_service.py`，要么删掉这段逻辑 |
| 4 | `XQ_TOKEN` / 雪球数据未接入 | 已确认 | 全仓无代码读取；仅 `.env.example`/`compose.yaml`/README | 明确从范围内移除或补实现 |
| 5 | Python `audit.py` 的 skill 路由审计未迁（只迁了引用校验） | 待确认是否有意 | 无 `audit_answer`/`loaded_skills` 对应物 | 需要作者确认 |
| 6 | 自选股/watchlist、桌面端、Streamlit、CLI、`stock/briefing.py`、`finance_scheduler.py` 未迁 | 已确认（按设计非目标或延期） | 迁移设计「非目标」 | 除 `finance_scheduler` 外均为明确非目标 |
| 7 | 金标准覆盖不完整：`get_research_notes` 仅 2 家；`601318` 缺 8 张缓存表；`600600`/`601606` 缺回购；`600600`/`601318` 缺解禁；`601606` 缺内部人 | 已确认 | README；`golden/tools` 文件清单 | 换公司回放会「缺数据」，不是 bug |
| 8 | MySQL 方言迁移、Testcontainers、Redis/RabbitMQ 实际接入 | 计划实现（阶段 4） | 迁移设计「阶段 4」；无代码 | 见 §5 |
| 9 | 应用日志 JSON、指标、Tracing、OTLP | 部分实现（阶段 4） | `/api/**` 审计日志已写 `var/logs/audit.log`（JSON Lines）；指标走 Micrometer/`/api/metrics`；OTel traces 按需经 OTLP/HTTP 导出（`TracingConfig` + AgentScope `OtelTracingMiddleware`，需 `OTEL_EXPORTER_OTLP_ENDPOINT`）；应用日志仍非 JSON，日志里没有 traceId | 见 §5 |

### 1.1 模拟交易（2026-11-07）

| # | 问题 | 状态 | 证据 | 影响 |
| --- | --- | --- | --- | --- |
| T1 | 成交按报价直接全成/全不成，无滑点、无部分成交、无盘口深度 | 已确认（模拟盘设计） | `TradingExecutor` | 收益偏乐观；只适合看流程与策略纪律 |
| T2 | 盘前候选依赖基本面工具与模型判断，可能一天都不出计划（「今天不交易」也是合法结论） | 已确认 | `TradingService`；`save_trade_plan` 允许空 items | 空计划日没有成交，不是故障 |
| T3 | 交易日判定依赖新浪报价日期；周末不探测，页面显示「交易日未确认」而非「休市」 | 已确认 | `TradingDayGate`、`TradingScheduler` 的 MON-FRI cron | 行为等价（不运行），只是文案 |
| T4 | 9:30 前拿到的报价可能是昨收/集合竞价，报价日期刷新时点取决于上游 | 待确认 | 需真实交易日观察 | 极端情况下门禁要多等几个 5 分钟窗口 |
| T5 | 阶段超时只中断线程，若引擎不响应中断仍可能残留半成品（阶段已标 ERROR） | 已确认 | `TradingScheduler.runPhase` | 唯一副作用是可能多一次上游调用 |
| T6 | 交易页无自动化测试；SSE/思考流只能人工看 | 已确认 | Task 14 手测记录 | 前端回归靠人工 |
| T7 | 交易无手续费之外的税费/融资成本，T+1 只按「今日买入」数量扣减可用 | 已确认（模拟盘简化） | `TradingRules` | 与真实券商略有差异 |
| T8 | 多实例部署时会重复调度 | 已确认（单实例前提） | `TradingScheduler` 无分布式锁 | 与迁移设计「单实例前提」一致 |

## 2. 已知缺陷与技术债

### 2.1 阶段 3 评审记录、已决定延期（不要当新问题修）

| # | 问题 | 位置 | 影响 |
| --- | --- | --- | --- |
| 1 | `ReviewAdapter` 的 `earliest` 解析不在错误隔离内，坏存档行会抛 NPE/`DateTimeParseException` | `ReviewAdapter` | 手改过库时复盘工具报「工具失败」而非「复盘失败」 |
| 2 | `DailyReportStore.load` 对 JSON 字面量 `null` 会 NPE，应跳过 | `DailyReportStore` | 坏行导致复盘整体失败 |
| 3 | `MarketAdapterTest.startStubbed` 重复启动 WireMock 且只停最后一个 | `data/src/test/.../market` | 测试线程/端口泄漏，长期跑可能变慢 |
| 4 | `ReviewAdapter` 的板块名→代码映射缓存不过期 | `ReviewAdapter` | 板块改名/新增要重启 |
| 5 | `get_sector_rotation` / `get_news_events` / `screen_candidates` 的 `limit=0` 与 Python 的 `x or default` 语义不同 | 三个工具类 | 显式传 0 时 Java 钳到最小值，Python 用默认值 |

### 2.2 其他代码级问题（本次文档盘点新增）

| # | 问题 | 位置 | 严重度 |
| --- | --- | --- | --- |
| 6 | `AgentEngine` 694 行上帝类，事件映射/缓存/引用/运行记录耦合 | `core/agent/AgentEngine.java` | 中 |
| 7 | 工具层重复解析帮助函数；两个 memory 工具重复 map 强转 | `CompanyTool` vs `ResolveCompanyTool`；`AppendResearchRecordTool`/`UpdateResearchRecordTool` | 低 |
| 8 | ~~`ToolCallCache` 无容量/TTL 上限~~ **已修**：每会话 200 条、最多 500 个会话、30 分钟 TTL，均按 LRU/访问时间淘汰 | `core/agent/ToolCallCache.java`；`ToolCallCacheTest` 7 个用例 | — |
| 9 | `FileRunStore.prune` 把目录内所有 `*.json` 当运行文件 | `FileRunStore.java:100` | 低 |
| 10 | 大盘词用宽泛子串匹配，无关问题可能被路由成大盘会话 | `QuestionRouter.java:21-35` | 中（影响用户体验） |
| 11 | `ConversationController` 每请求多次 `find`/`messages`，无事务 | `ConversationController.java` | 低 |
| 12 | 数据层 `catch (RuntimeException)` 约 71 处；多数会 `throw` 或写进 `ToolResult.errors`（已暴露给调用方），真正静默吞掉的约 11 处**已补日志**（退化到缓存/空值处用 WARN，纯解析兜底用 DEBUG）。剩余宽泛 catch 是有意的降级路径，不再逐处改窄 | `data/src/main` | 低（排障已可定位） |
| 13 | `PledgeAdapter.daysSince` 用 `LocalDate.now()` 而非注入的 `Clock` | `PledgeAdapter.java:516` | 低（测试时钟与 stale 判断可能不一致） |
| 14 | `DividendAdapter` 直接读 `fin_income`，不先确保财报新鲜 | `DividendAdapter.java:257` 的 `shortcut:` | 低（分红率可能用旧利润） |
| 15 | 公司代码/交易所前缀归一化有 4+ 份重复实现 | `StockDirectoryAdapter`、`F10Support`、`FinanceSupport`、`EastmoneySupport` | 低 |
| 16 | 若干阈值/上限是分散的魔法数字（并发 2/4/6/8/10、TTL、分页 100/80/60） | 各适配器 | 低（可调性差） |
| 17 | 进程内缓存（板块 600s、筛选/复盘 900s）重启即失、多实例不共享 | `SectorAdapter`、`ScreenAdapter`、`ReviewAdapter` | 中（未来多实例时） |
| 18 | `MemoryImportRunner` 只在档案为空时导入；中止后不会重试 | `MemoryImportRunner.java` | 低 |
| 19 | ~~`AskController` 超时只结束 emitter，不取消后台任务~~ **已修**：超时 `FutureTask.cancel(true)` 中断工作线程，`AgentEngine` 清中断标记后再落盘 | `AskController.java`；`AgentEngine`；`AskControllerTest` | — |
| 20 | 未使用的符号：`AgentProperties.defaults()`、`Screening.SHORTLIST_SIZE`、`SectorRotation.LEADER_LIMIT`、`Review.MAX_REPORTS` | core | 低 |
| 21 | `ContextMeter` 为校验完整解析 `tool-schemas.json` 再丢弃 | `ContextMeter.java:61` | 低 |
| 22 | `ResolveCompanyTool` 的错误文案写成「company 参数…」，实参名是 `name_or_code` | `ResolveCompanyTool.java:48` | 低（误导模型） |
| 23 | 测试默认地址用了 `push2test` 域名 | `StockMasterSync.java:45` | 中（见 §3） |
| 24 | `V7__announcement.sql` 缺末尾分号（Flyway 仍能执行） | 迁移文件 | 极低 |
| 25 | 没有 CI 配置 | 仓库根 | 中（回归靠人工） |

## 3. 安全与运维风险

| # | 风险 | 证据 | 处理建议 |
| --- | --- | --- | --- |
| 1 | **本机 IDE 配置里疑似存在真实模型密钥**（不在 git 中，但文件在磁盘上） | `.idea/workspace.xml` 第 63 行附近（`.idea/` 已被忽略） | 交接或共享目录前清理该文件；如确为真实 key，**轮换**。本次文档不复制其值 |
| 2 | 明文 HTTP + 浏览器 `localStorage` 长期令牌 | README 部署风险段 | 公网必须前置 TLS 反代或走 VPN/SSH 隧道 |
| 3 | `APP_API_TOKEN` 只校验非空 + ASCII，不校验最小长度 | `ApiTokenProperties`；`.env.example` 说 ≥32 | 部署时用 `openssl rand -hex 32`；代码层可补长度校验 |
| 4 | 管理端口 8081 只听回环且只暴露 health | `application.yml` | 保持；不要在 compose 里发布 |
| 5 | `XQ_TOKEN` 通过 compose 注入但无人使用 | 见 §1 #4 | 要么删，要么实现，避免「以为配了就生效」 |
| 6 | 无速率限制；审计日志已补（`/api/**` 单行 JSON 写 `var/logs/audit.log`） | `AuditLogFilter`；代码中无速率限制 | 单用户场景可接受；公网暴露前评估限流 |
| 7 | 运行原文与消息 payload 含原始上游数据，可能含敏感信息 | `var/runs`、`message.payload_json` | 备份/导出时注意脱敏 |

## 4. 文档与配置不一致

| # | 问题 | 位置 |
| --- | --- | --- |
| 1 | `frontend/README.md` 仍写 Streamlit `python -m assistant.api`、8502 端口、`data/run_logs/` | `frontend/README.md:23,39` |
| 2 | README 说 `spring-boot:run` 工作目录是 `app/`，实际被 `spring-boot-maven-plugin.workingDirectory` 设成仓库根（已实测：DB 落在 `./var/app.db`、`/` 200）；README 的 `APP_SKILLS_DIR=../skills`、`static-locations=../frontend` 写法过时 | `app/pom.xml:73-75` vs `README.md:36-40`；**已确认** |
| 3 | `ConversationController` 注释仍说 `RunStore`/`AssistantEngine` bean「Task 20 之后才有」，实际一直存在 | `ConversationController.java:35-36` |
| 4 | `ApiConfig` 注释提到「Phase 1」与 10 分钟流式 | `ApiConfig.java:10-13` |
| 5 | 迁移设计说 33 个工具、15 个 skill；README 说 33 个工具 | 差异来自 `load_skill`：schema 有 34 条（另含 Java 新增 `web_search`），Java 注册 33 个，`load_skill` 由框架提供 |
| 6 | 迁移设计写 `data/app.db`，实际默认 `./var/app.db` | `application.yml` 以代码为准 |

## 5. 阶段 4 / 5 待办（来自迁移设计，均未开始）

**阶段 4 硬化**

1. 全量对照测试（补金标准缺口、真实上游 live 测试的定期跑法）。当前只有可选的 `LiveSkillSmoke` 覆盖 skill 路由，完整问答仍靠人工。
2. 慢查询与并发压测（SQLite 写并发、SSE 并发、上游并发 4 是否够）。
3. 日志 JSON + traceId/conversationId/runId；指标（HTTP/Agent 轮次/工具耗时/模型 token，现有 `/api/metrics` 只覆盖工具与进程）；Tracing/OTLP（traces 已接，日志/指标未导出）。
4. 备份恢复演练。
5. MySQL profile：`db/migration/mysql` + Testcontainers 跑通全部迁移与 Mapper 测试。
6. Redis / RabbitMQ profile 至少能启动。
7. 补 CI（`mvn verify` + `spotless:check`）。

**阶段 5 切换**

1. 云服务器部署、只开 80（或加 Caddy TLS）。
2. Python 版停对外服务，保留代码与金标准夹具。
3. 连续使用一周无阻断问题。

## 6. 交接后第一件该做的事

1. 先跑 `./mvnw verify` + `./mvnw spotless:check`，确认与本文记录一致（core 130 / data 134 / app 81）。若数字变了，先看 `git log` 有没有新提交。
2. 用真实 `DEEPSEEK_API_KEY` + 真实网络跑一次完整问答（离线环境无法代跑），重点看：引用 id 是否出现、运行详情是否有模型/工具阶段、刷新后能否回放。
3. 再决定是否动 §2 的技术债；**不要**在没有实跑基线前重构 `AgentEngine` 或适配器。
