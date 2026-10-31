# 模块详解

按包列关键类、关键方法与常量。方法签名以源码为准；未列的私有实现细节请直接读文件。

## 1. `core`

### 1.1 `core.service`

| 类 | 职责 | 关键成员 |
| --- | --- | --- |
| `AskService` | 一次提问的编排：路由 → 建会话 → 落用户消息 → 调引擎 → 落助手消息 → 发 SSE | `ask(Long conversationId, String question, EventSink sink)`；`static buildTitle(String name, String question)`；常量 `UNKNOWN_COMPANY_WARNING`、`METER_INTERVAL_MS=400`、`TITLE_LIMIT=30`、`PAGE_ISSUE_LIMIT=2` |
| `QuestionRouter` | 公司/大盘识别与「是否换会话」 | `Route route(String currentCode, String currentName, String question)`；`MARKET_CODE="MARKET"`、`MARKET_NAME="大盘"`、`SIX_DIGITS="(?<!\d)(\d{6})(?!\d)"` |
| `CompanyResolver` | 工具层的公司解析（代码/名称/模糊候选） | `ResolvedCompany resolve(String query, String fallback)`；`MAX_CANDIDATES=5` |
| `ContextMeter` | 上下文占用估算 | `Map usage(long cid, int draftTokens)`；`estimateTokens(String)`；`BUDGET_TOKENS=55_000`、`HARD_TOKENS=80_000`；`FIXED_TOKENS` 由 system.md + tool-schemas.json 计算 |
| `EventSink` | SSE 发送抽象 | `send(Map<String,Object>)`、`complete()` |
| `Route` / `ResolvedCompany` | 路由/解析结果 record | `Route(code,name,reset)`、`ResolvedCompany(code,error,candidates)` |

路由规则（`QuestionRouter.java:46-64`）：

1. 问题里能解析出公司（六位代码优先，然后名称；同一位置取更长的名称）→ 用该公司，`reset = code != currentCode`。
2. 否则若命中大盘词（`MARKET_WORDS` 子串匹配）：仅当当前会话是公司、且问题里出现指代词（它/该股/这家/该公司/这只/此股）时保留当前公司；否则 `Route("MARKET","大盘", reset = currentCode != "MARKET")`。
3. 否则保留当前会话。

### 1.2 `core.agent`

| 类 | 职责 | 关键成员 |
| --- | --- | --- |
| `AgentEngine` | 装配 `HarnessAgent`；把 AgentScope 事件映射成 SSE/运行记录；引用校验；工具缓存 | `AskResult ask(long, String code, String name, String question, StreamListener)`、`deleteSession(long)`；`UNCACHED_TOOLS`（17 个）、`CompanyPromptMiddleware(order=100)` |
| `AgentFactory` | 用 `ModelRegistry.resolve(model, apiKey)` 创建引擎 | `static AssistantEngine create(ToolRegistry, AgentProperties, String apiKey)` |
| `AgentProperties` | 模型/skill/数据目录/轮次 | record `(model, skillsDir, dataDir, maxTurns)`；默认 `deepseek:deepseek-v4-flash`、`skills`、`data`、`10` |
| `AssistantEngine` | 引擎接口 | `ask(...)`、`deleteSession(...)` |
| `AskResult` | 一次问答结果 | record `(text, sources, trace, issues, run, raw)` |
| `EngineFailure` | 带运行记录的引擎异常 | `RunRecord run()` |
| `StreamListener` | 流式回调 | `text(String)`、`discard()`、`tool(ToolEvent)` |
| `ToolCallCache` | 会话内工具结果缓存 | `get/put/clear`；key = 工具名 + 规范化参数（去 `company`）+ 公司；每会话 200 条、500 会话、30 分钟 TTL，LRU 淘汰 |
| `ToolEvent` | 工具事件 | record `(name, company, ok, cached, durationMs)` |

`buildAgent` 关键参数：name `research-assistant`、system=`prompts/system.md`、`maxIters(maxTurns)`、`temperature(0.3)`、`skillRepository(FileSystemSkillRepository)`、`workspace(dataDir/workspace)`、`stateStore(JsonFileAgentStateStore(dataDir/sessions))`、`compaction(triggerTokens(80_000))`；关闭文件系统/Shell/Web/记忆/转写/子代理/动态 skill 等能力；最后移除 harness 自带异步工具，只保留注册表工具 + `load_skill_through_path`。

事件映射（`handleEvent`）：

| AgentScope 事件 | 处理 |
| --- | --- |
| `MODEL_CALL_START` | 轮次 +1；`EXCEED_MAX_ITERS` 触发时标记 `forced` |
| `MODEL_CALL_END` | 写模型 stage；有 usage → `usage_source="api"`，否则 `"estimate"` |
| `TEXT_BLOCK_DELTA` | 累积文本 + `listener.text(delta)` |
| `TOOL_CALL_START` | `listener.discard()`，记开始时间 |
| `TOOL_RESULT_END` | 成功 → `RunState.record`（Sources/TraceStep/raw/stage/listener.tool）；参数校验失败或未知工具 → 补失败 trace/stage |
| `AGENT_RESULT` | 取最终消息 |
| `EXCEED_MAX_ITERS` | 标记轮次用尽，下一轮强制作答（不计数） |

结束后：`CitationValidator.validate` → 追加 issues → `recorder.finish` → `AskResult`。异常：`recorder.finish("error", msg)` 后抛 `EngineFailure(msg, run, cause)`。

### 1.3 `core.tool`

| 类 | 职责 |
| --- | --- |
| `ResearchTool` | 接口：`ToolSchema schema()`、`ToolResult execute(Map args, String defaultCompany)` |
| `ToolRegistry` | 注册/校验/分发；重复或 null 抛 `IllegalArgumentException`；未知工具返回 `ToolResult.failure(null,"未知工具：X")`；工具异常转 failure；每次真实执行写入 `ToolMetrics` |
| `ToolMetrics` | 进程内工具执行统计；`record(name, ms, ok)`、`snapshot()`；每工具保留最近 500 个耗时样本算 P95/P99 |
| `ToolSchemas` | 读 `/tool-schemas.json`；未知 schema 抛 `IllegalStateException` |
| `CompanyTool` | 公司类工具基类：解析 `company`、钳位、错误文案 |
| `ToolArguments` | int/double/text 解析 |
| `MemoryToolSupport` | 记忆工具共用的 section/必填字段/时间格式 |
| 32 个具体工具 | 见 `API_AND_INTERFACE.md` §3.3；`CalcValuationTool` 支持 check/scenario/terminal |

公司类工具的参数默认值与钳位统一写在各自的 `CompanyTool` 子类里（见 `API_AND_INTERFACE.md` 表）。

### 1.4 `core.research`（纯函数，与 Python 逐条对齐）

| 类 | 职责 | 关键常量 |
| --- | --- | --- |
| `MarketState` | 大盘分类 | `STRONG="强势"`、`RANGE="震荡"`、`WEAK="弱势"`、`EXTREME="极端风险"`；`BENCHMARK_CODE="sh000300"` |
| `NewsEvents` | 事件构造/去重/选择 | `NEWS_LIMIT=12`、`MAX_SUMMARY=260`；分类配额 政策6/产业4/公司3/海外3 |
| `SectorRotation` | 板块相对强度 | 权重 today .10 / ret_5d .30 / ret_20d .25 / ret_60d .15 / breadth .10 / persistence .05 / ma20 .05；`RS_WINDOW=10` |
| `Screening` | 量化筛选 | `MIN_BARS=40`、`DEFAULT_LIMIT=15`、`MAX_PER_BOARD=4`、`EXCLUDED_BOARDS={创业板,科创板,北交所}`、权重 trend .35/momentum .30/position .20/volume .15 |
| `Strategy` | 条件式仓位 | `TOLERANCES={保守,稳健,积极}`、`RISK_MULTIPLIER` 低1.0/中1.0/高0.85/极高0.7、`REDUCE_OVER=5`、`AT_CAP_BAND=5`、`BUY_HEADROOM=15` |
| `Review` | 复盘 | `HORIZON=5`、`MAX_REPORTS=20`、`MAX_CANDIDATES=8`、`ROUND_TRIP_COST_PCT=0.12`、`RANGE_BAND_PCT=3.0` |
| `MemoryRules` | 档案校验 | 六组枚举白名单；单字段 20KB / 单记录 50KB；id 正则 `F\d{3,}`/`H\d{3,}`；`dedupeKey` |
| `MemoryException` | 档案业务异常 | — |

### 1.5 `core.run`

| 类 | 职责 |
| --- | --- |
| `RunRecorder` | 累积运行阶段，`payload()` 输出与 Python `run_logs` 兼容的 JSON |
| `FileRunStore` | 原子写、读、按 mtime 保留 200 份、`validId` 校验（正则 + 禁 `..`） |
| `RunRecord` / `RunSummary` | `(runId,payload)` / `(id,fileBytes)` |

### 1.6 `core.citation`

`CitationValidator.validate(text, registry)` → `CitationCheck(text, citations, issues)`：

- 匹配 `\[\[([^\[\]]+)\]\]`，去掉首尾空白。
- 未知 id → 文本替换为 `[?]`，每个出现记一条 `unknown_citation`（不去重）。
- 已知 id 保留原文、按首次出现排序、去重。
- 清洗后若无任何有效引用但文本含 Unicode 数字 → 追加「回答里有数字，但没有任何有效引用」。

### 1.7 `core.valuation` 与 `core.domain`

- `ValuationMath`：`BigDecimal` + `MathContext(28, HALF_EVEN)`；`compare`、`fcfYield`、`scenario`、`terminalPe`；`PASS_PCT=1`、`WARN_PCT=5`、`MIN_SPREAD_PCT=5`。
- `ToolResult` 是核心值对象，见 `API_AND_INTERFACE.md` §3.1。

## 2. `data`

### 2.1 上游

| 类 | 职责 |
| --- | --- |
| `UpstreamClient` | `get(url, headers)` / `get(url, charset, headers)` |
| `RestClientUpstreamClient` | 直连、UA、60s、2 次尝试、并发 4；`MAX_ATTEMPTS=2`、`MAX_CONCURRENT=4`、`RETRY_DELAY_MS=500` |
| `UpstreamException` | `status`（0 = 网络错误）+ 去查询串的 URL |

### 2.2 适配器一览

| 适配器 | 端口 | 上游 / 存储 |
| --- | --- | --- |
| `StockDirectoryAdapter` | `CompanyDirectory` | 懒加载 `StockMasterSync`；`FAILURE_COOLDOWN_MS=60_000` |
| `CompanyProfileAdapter` | `CompanyProfilePort` | F10 CompanySurvey；`company_profile`，30 天 |
| `SegmentAdapter` | `SegmentPort` | F10 BusinessAnalysis；`biz_segment` |
| `FinanceAdapter` | `FinancePort` | 新浪财报三表；`fin_*`，7 天 |
| `FinancialHistoryAdapter` | `FinancialHistoryPort` | 复用 `FinanceAdapter.freshHistory`，只取年报 |
| `ManagementAdapter` | `ManagementPort` | 同花顺 `mapp/.../a_manager_introduce.json`；7 天 |
| `ShareholderAdapter` | `ShareholderPort` | F10 `ShareholderResearch/PageAjax`；24h |
| `DividendAdapter` | `DividendPort` | `RPT_SHAREBONUS_DET`；24h；读 `fin_income` |
| `InsiderTradeAdapter` | `InsiderTradePort` | `RPT_EXECUTIVE_HOLD_DETAILS`；`MAX_EVENTS=60` |
| `LockupAdapter` | `LockupPort` | `RPT_LIFT_STAGE`；`MAX_EVENTS=40` |
| `PledgeAdapter` | `PledgePort` | `RPT_CSDC_LIST`；阈值 50/30/1pp |
| `BuybackAdapter` | `BuybackPort` | `RPTA_WEB_GETHGLIST_NEW`；`MAX_PLANS=30` |
| `ForecastAdapter` | `ForecastPort` | `RPT_PUBLIC_OP_NEWPREDICT`；`MAX_EVENTS=30` |
| `ResearchAdapter` | `ResearchPort` | `RPT_ORG_SURVEY`；`MAX_EVENTS=20` |
| `ResearchNotesAdapter` | `ResearchNotesPort` | 调研纪要 Q/A 拆段；30 天 |
| `AnalystViewAdapter` | `AnalystViewPort` | `reportapi.eastmoney.com/report/list`；`MAX_REPORTS=30` |
| `PeerAdapter` | `PeerPort` | `RPT_LICO_FN_CPD` + 估值；`company_peers` 6h |
| `ValuationAdapter` | `ValuationPort` | `RPT_VALUEANALYSIS_DET`；`val_history`/`val_peer` 6h |
| `CalcValuationAdapter` | `ValuationCheckPort` | 无上游，交叉校验 PE/PB/FCF |
| `MarketAdapter` | `MarketPort` | 新浪指数 + 乐咕 + 同花顺板块 + 东财涨停 + 财联社/东财/央视新闻 + 腾讯/新浪海外 |
| `NewsAdapter` | `NewsPort` | `CLS + 东财 + 央视 + 海外`，`NewsEvents` 选事件 |
| `SectorAdapter` | `SectorPort` | 同花顺行业 + 板块日线 + 基准；进程内 600s |
| `ScreenAdapter` | `ScreenPort` | 新浪全市场快照 + 东财日线；`HISTORY_DAYS=140`、`MAX_WORKERS=10`、进程内 900s |
| `StrategyAdapter` | `StrategyPort` | 纯计算；市场状态缺省时调 `MarketPort.overview()` |
| `ReviewAdapter` | `ReviewPort` | 存档 + 复盘；`MAX_WORKERS=8`、`HISTORY_LIMIT=400`、进程内 900s |
| `ResearchMemoryStore` | `ResearchMemoryPort` | 档案六表；`append` 串行化 |

### 2.3 持久化

- MyBatis-Plus：`BaseMapper` 做 CRUD，`@TableId(INPUT)`（仅 `ConversationEntity` 用 AUTO）；`StockMasterSync` 与 `PeerAdapter` 用 `insertOrUpdate`；复合主键表（`val_*`、`biz_segment`、`fin_*`、`company_profile`）用 `TransactionTemplate` 里 delete+insert。
- `JdbcTemplate` 存储：`ResearchCacheStore`（白名单表名，`ResearchCache` 枚举 10 项）、`DailyReportStore`、`ResearchMemoryStore`。
- `ConversationRepositoryImpl`：`create/find/recent/messages/append/updateUsage/delete`；`append` 在事务里用 `INSERT ... SELECT MAX(seq)+1 ... RETURNING seq` 取序号并 touch 会话；读消息时跳过无法解析的 payload。

### 2.4 定时任务

`StockMasterSync.refresh`：`@Scheduled(cron="0 0 6 * * *")`，`PAGE_SIZE=100`、字段 `f12,f14`、市场参数 `m:0+t:6,m:0+t:80,m:1+t:2,m:1+t:23`，先抓完所有页再一个事务写入。

## 3. `app`

| 类 | 关键点 |
| --- | --- |
| `Application` | `@EnableScheduling`；排除 `UserDetailsServiceAutoConfiguration`（避免启动日志打印随机密码） |
| `ApiConfig` | `askExecutor` = 虚拟线程池 |
| `AskController` | `POST /api/ask`；`TIMEOUT_MS=600_000`；空问题 400；`X-Accel-Buffering: no` |
| `ConversationController` | 5 个路由；`RECENT_LIMIT=50`；`RUN_ID` 正则；删除顺序：消息 → 运行文件 → Agent 会话 |
| `ResearchController` | 列表/详情；`code` 必须六位；`limit` 1..500（默认 200） |
| `MetricsController` | `GET /api/metrics`；JDK `OperatingSystemMXBean` / `MemoryMXBean` + `ToolRegistry.metrics()` |
| `HealthController` | `/health` → `ok` |
| `SseWriter` | `data: <json>\n\n`；断连后静默 |
| `AssistantConfig` | 全部核心 Bean；缺 key → `UnavailableEngine("请先设置环境变量 DEEPSEEK_API_KEY")` |
| `MemoryImportRunner` | `APP_MEMORY_IMPORT` 导入 |
| `SecurityConfig` / `TokenAuthFilter` / `ApiTokenProperties` | 令牌鉴权；无状态；401 JSON |

## 4. 前端（`frontend/`）

| 文件 | 关键点 |
| --- | --- |
| `app.js` | 令牌 `localStorage["ir_token"]` + 401 重试；markdown + DOMPurify + `[[id]]` 渲染；`ChatPane`；运行详情 timeline/prompt/context 三个标签；档案页只读 |
| `index.html` | 左会话列表、分栏聊天、档案页、全局来源侧栏、`<chat-pane>` 模板、运行详情面板 |
| `boot.js` | 启动视频，Esc/空格/回车跳过，12s 兜底，5s 卡顿检测 |
| `styles.css` | 全部样式 |
| `assets/` | 本地内置 Vue/marked/DOMPurify + 启动视频 |

SSE 消费：`fetch` 读流 → 按 `\n\n` 切帧 → 解析 `data:` JSON → 按 `type` 分发（`state/text/discard/tool/meter/done/error/warning`）。
