# 项目知识地图

按问题索引代码。所有路径相对于仓库根 `D:\Programming\investment-research`。

## Q1. 系统从哪里启动？

1. `app/src/main/java/com/investmentresearch/app/Application.java`
   - `main`：先 `Files.createDirectories(APP_DATA_DIR)`（默认 `./var`），再 `SpringApplication.run`。
   - `@SpringBootApplication(scanBasePackages="com.investmentresearch", exclude=UserDetailsServiceAutoConfiguration.class)`。
   - `@EnableScheduling`：让 `StockMasterSync.refresh` 生效。
2. Spring 启动装配：
   - `app/.../config/AssistantConfig.java`：工具注册表、AgentProperties、RunStore、QuestionRouter、ContextMeter、AssistantEngine、AskService。
   - `app/.../security/ApiTokenProperties` 在启动期校验令牌（空白/非 ASCII → 启动失败）。
   - `data/.../config/MybatisPlusConfig`（Mapper 扫描）、`UpstreamConfig`（HTTP 客户端）。
   - Flyway 自动执行 `classpath:db/migration/sqlite`（在 `data` 模块资源）。
   - `app/.../config/MemoryImportRunner` 作为 `ApplicationRunner` 最后执行旧档案导入。
3. 端口：应用 8080；管理 8081（127.0.0.1，只暴露 health）。

## Q2. 一个请求经过哪些类？

以 `POST /api/ask` 为例（行号为近似定位）：

```text
TokenAuthFilter.doFilterInternal                     app/security/TokenAuthFilter.java
  → SecurityConfig 的 chain（无状态，/api/** 需认证）
  → AskController.ask(@RequestBody AskRequest)       app/api/AskController.java
      · 空问题 → 400 {"error":"empty question"}
      · new SseEmitter(600_000)
      · askExecutor.submit(() -> stream(...))        app/api/ApiConfig.java（虚拟线程）
  → AskService.ask(conversationId, question, sink)   core/service/AskService.java:60
      → ConversationRepository.find(id)              data/persistence/ConversationRepositoryImpl
      → QuestionRouter.route(...)                    core/service/QuestionRouter.java:46
      → ConversationRepository.create(...)           （必要时）
      → ConversationRepository.append(user)          data/persistence/...
      → AgentEngine.ask(...)                         core/agent/AgentEngine.java:121
          → HarnessAgent.streamEvents(...)            AgentScope 2.0.4
          → CachedTool.execute(...)                   core/agent/AgentEngine.java（内部类）
              → ToolRegistry.run(...)                 core/tool/ToolRegistry.java
                  → 具体 Tool.execute(...)             core/tool/*Tool.java
                      → core.port.*Port                 core/port/*.java
                          → data.adapter.*                data/adapter/**
                              → RestClientUpstreamClient  data/upstream/...
                              → 各类 Mapper/JdbcTemplate  data/persistence/**
          → CitationValidator.validate(...)           core/citation/CitationValidator.java
      → RunStore.write(run.payload())                 core/run/FileRunStore.java
      → ConversationRepository.append(assistant)      data/persistence/...
      → ConversationRepository.updateUsage(...)
      → EventSink.send(done)                          app/api/SseWriter.java
  → SseWriter.complete() → 连接关闭
```

其他路由：

- `GET /api/conversations/**` → `ConversationController` → `ConversationRepository` / `RunStore` / `AssistantEngine.deleteSession`。
- `GET /api/research/companies*` → `ResearchController` → `ResearchMemoryPort`（`ResearchMemoryStore`）。

## Q3. Controller → Service 的调用关系

| Controller | 注入的 Service/端口 | 说明 |
| --- | --- | --- |
| `AskController` | `AskService`、`AssistantEngine`（判 `UnavailableEngine`）、`Executor askExecutor`、`ObjectMapper` | `AskService` 是唯一编排入口 |
| `ConversationController` | `ConversationRepository`、`RunStore`、`AssistantEngine` | 删除会话时调 `engine.deleteSession` |
| `ResearchController` | `ResearchMemoryPort` | 只读 |
| `HealthController` | 无 | 返回 `ok` |

`AskService` 的依赖：`ConversationRepository`、`AssistantEngine`、`QuestionRouter`、`ContextMeter`、`RunStore`（都来自 `AssistantConfig`）。

## Q4. 数据在哪里获取、处理、存储？

- **获取**：`data/adapter/**` 的适配器，统一走 `data/upstream/RestClientUpstreamClient`（禁代理、Chrome UA、60s、2 次、并发 4）。数据源矩阵见 `DATA_AND_DATABASE.md` §5。
- **处理**：适配器内解析（`EastmoneySupport`/`F10Support`/`FinanceSupport`/`MarketSupport` 等）；纯规则在 `core/research/**`（`MarketState`、`NewsEvents`、`SectorRotation`、`Screening`、`Strategy`、`Review`）与 `core/valuation/ValuationMath`。
- **存储**：
  - SQLite `var/app.db`：会话/消息（`ConversationRepositoryImpl`）、各类缓存表（适配器 + `ResearchCacheStore`）、档案（`ResearchMemoryStore`）、复盘（`DailyReportStore`）。
  - 文件：`var/runs/*.json`（`FileRunStore`）、`var/sessions/**`（`JsonFileAgentStateStore`）。
  - 进程内：适配器短 TTL 缓存、`ToolCallCache`。

## Q5. AI 请求从哪里发起？

唯一入口：`core/agent/AgentEngine.ask` → `agent.streamEvents(userMessage, context)`（`AgentEngine.java:155` 附近）。

- 引擎实例在 `AgentFactory.create(...)` 中构造：`ModelRegistry.resolve(properties.model(), ModelCreationContext.builder().apiKey(apiKey).build())`。
- 模型名默认 `deepseek:deepseek-v4-flash`（`AgentProperties.DEFAULT_MODEL`）。
- API key 只在 `AssistantConfig.assistantEngine` 里从环境变量 `DEEPSEEK_API_KEY` 读取；没有 key 时返回 `UnavailableEngine`，**不构造**任何模型客户端。
- 没有任何适配器或工具直接调模型；上游 HTTP 与模型调用完全分离。

## Q6. Prompt 在哪里定义？

| Prompt | 位置 | 装配方式 |
| --- | --- | --- |
| 系统提示词 | `core/src/main/resources/prompts/system.md`（35 行） | `AgentEngine.SYSTEM_PROMPT = loadSystemPrompt()`，传给 `HarnessAgent` 的 `sysPrompt` |
| 追加的运行时提示 | `AgentEngine.CompanyPromptMiddleware`（order=100） | 每次模型调用前追加「当前默认公司：…」与「本轮工具调用最多 N 轮…」 |
| skill 正文 | `skills/<name>/SKILL.md`（15 个） | `FileSystemSkillRepository` 扫描；模型通过 `load_skill_through_path` 取用 |
| 工具描述 | `core/src/main/resources/tool-schemas.json` | 生成物；`ToolSchemas.loadDefault()` 读取，注册表 `schema()` 返回 |
| 记忆摘要 | `MemoryToolSupport` / 各 memory 工具的输出文本 | 工具内构造，不是独立 prompt 文件 |

## Q7. 工具如何注册、如何调用？

**注册**

1. `AssistantConfig.toolRegistry(...)` 接收 `CompanyDirectory` + 27 个数据端口 Bean（共 30 个端口中的这些），`new` 出 33 个工具，前 32 个按 Python 顺序放进 `List<ResearchTool>`，末尾追加 `web_search`。
2. `new ToolRegistry(list)`：拒绝 null 与重名；`schemas()` 按注册顺序返回。
3. `AgentEngine.buildAgent`：`Toolkit` 注册注册表工具 + AgentScope 的 `SkillLoadTool`；之后移除 harness 自带的异步工具，只留这两类。

**调用**

1. 模型发起 tool call → AgentScope 回调 `CachedTool.execute(...)`。
2. `CachedTool`：查 `ToolCallCache`（key = 工具名 + 规范化参数 + 公司，排除 `company` 字段）；命中 → `asCached()`；不命中 → `ToolRegistry.run(name, args, defaultCompany)`。
3. `ToolRegistry.run`：
   - 未知工具 → `ToolResult.failure(null, "未知工具：" + name)`；
   - 工具抛异常 → `ToolResult.failure(null, message)`；
   - 否则返回工具结果（读 `ToolResult` 的 `toMessageJson()` 作为模型消息内容）。
4. 结果回写：`RunState.record(...)` 记 sources/trace/raw/stage，`listener.tool(ToolEvent)` 发 SSE。

**哪些工具不缓存**：`AgentEngine.UNCACHED_TOOLS` 里的 17 个（`load_skill`、`resolve_company`、`get_valuation`、`get_announcements`、`get_earnings_forecasts`、`get_market_overview`、`get_news_events`、`get_sector_rotation`、`screen_candidates`、`build_strategy_plan`、`save_daily_judgment`、`review_research_reports`、5 个 memory 工具）。

## Q8. 上下文如何管理？

| 机制 | 位置 | 行为 |
| --- | --- | --- |
| 框架压缩 | `AgentEngine.buildAgent` 的 `CompactionConfig(triggerTokens(properties.compactionTokens()))`（默认 55_000，交易 120_000） | 超过阈值由 HarnessAgent 压缩 |
| 占用估算 | `ContextMeter.usage(cid, draftTokens)` | 固定成本（system.md + tool-schemas.json）+ 消息文本 + 每消息开销 + 草稿；预算 55k、硬上限 80k |
| 推送 | `AskService` 的 `listener` | 初始一次；文本每 ≥400ms；`discard`/`tool` 后归零 |
| 会话状态 | `JsonFileAgentStateStore(dataDir/sessions)` | 按 `sessionId=conversationId` 持久化 |
| 工具结果复用 | `ToolCallCache` | 同参数同公司复用，旧结果换 stub |
| 历史消息 | `message` 表 | Agent 状态与 UI 消息分离；删会话时两边都删 |

注意：`HARD_TOKENS=80_000` 只上报，不强制；真正的裁剪由框架压缩触发。

## Q9. 结果如何返回前端？

1. **流式**：`SseWriter.send` 发 `data: {json}\n\n`；事件类型与字段见 `API_AND_INTERFACE.md` §2。
2. **收尾**：`done.message` 带 `text/sources/trace/issues/run`；`done.usage` 是当前占用。
3. **落库**：`message` 表存同一份 message + `raw`；`conversation.usage_json` 存最近用量。
4. **回放**：`GET /api/conversations/{id}` 返回消息（清洗掉 `raw`），前端渲染。
5. **证据**：点击引用 → `GET /api/conversations/{id}/evidence?source_id=...` → 从该会话 `raw` 里找最后一条匹配的工具消息。
6. **运行详情**：`GET /api/conversations/{id}/runs/{runId}` → `FileRunStore.read` 返回原始 JSON；前端渲染时间线/prompt/context。
7. **前端**：`frontend/app.js` 的 `ChatPane` 负责解析 SSE、渲染 markdown、引用、meter、运行面板。

## Q10. 异常如何传播？

| 层次 | 处理 |
| --- | --- |
| 适配器 | 捕获上游/解析异常 → `ToolResult.failure(...)`；不向 Agent 抛 |
| 工具 | 参数解析失败 → `ToolResult.failure`；业务异常（如 `MemoryException`）在工具内转 failure（记忆写入失败会给必填字段提示） |
| `ToolRegistry` | 兜底 catch `RuntimeException` → `failure(null, message)` |
| `AgentEngine` | 事件处理内部错误 → 运行记录 `stop_reason="error"`，抛 `EngineFailure(message, run, cause)` |
| `AskService` | catch `RuntimeException` → 落一条 `role="error"` 消息（含 run）→ 发 `error` 事件 → 结束；`Sink` 吞掉客户端断连异常，不影响落库 |
| `AskController` | catch `Throwable` → 追加 error 事件并结束 emitter；空问题直接 400 |
| Controller 层 | `ConversationController`/`ResearchController` 把「不存在」映射为 404；参数非法映射为 400 |
| 安全层 | 未认证 → 401 JSON `{"error":"unauthorized"}` |
| 启动期 | 令牌缺失/非 ASCII → 启动失败；`tool-schemas.json` 缺失 → `IllegalStateException` |
| 前端 | 401 → 弹令牌输入；SSE `error` → 在气泡里展示「研究失败：…」并允许打开运行详情 |

**不会发生**：错误被静默丢弃后回答成功——`error` 事件与 `error` 消息至少有一条落地（`AskServiceTest` 覆盖）。

## Q11. 模拟交易 Agent 在哪里、怎么跑？

1. **入口**：`app/.../trading/TradingScheduler` 的 `@Scheduled` 方法——盘前 `0 25,30,35,40,45 9 * * MON-FRI`、午盘 `0 35 11 * * MON-FRI`、收盘 `0 5 15 * * MON-FRI`、盘中 `fixedDelayString = poll-seconds`（默认 10s）。
2. **交易日门禁**：`core/trading/TradingDayGate.status(date)` 用 `QuotePort.quotes(...)` 的 `quoteDate` 是否为今天判定；上游失败/日期不符 → `UNCONFIRMED`，09:45 仍未确认按休市处理，绝不按旧价下单。
3. **阶段**：`TradingService.runPhase(date, phase, listener)` → 独立 `tradingEngine`（`prompts/trading.md`）→ 模型调用工具（`get_trading_state` / `save_trade_plan` / `save_trading_journal`）→ 计划、复盘、阶段记录、run 原文落库。
4. **盘中成交**：`TradingExecutor.tick(date, now)` 取计划内代码的实时报价（不缓存），按 `TradingRules` 判定限价/止损/止盈，`TradingRepository.claim` 条件更新保证只成交一次；tick 最多 60s 落一次库。
5. **页面/接口**：`TradingView` 组装只读快照（不探测上游）→ `TradingController` 的 `/api/trading/state|dates|equity|phases/{id}|stream` → `frontend/app.js` 的 `openTrading/connectTradingStream`。
6. **持久化**：`data/persistence/TradingRepositoryImpl` + `V13__trading.sql` 九张表。
