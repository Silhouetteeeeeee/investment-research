# API 与接口

三组对外契约：HTTP/SSE（前端 ↔ `app`）、工具契约（模型 ↔ `core.tool`）、端口契约（`core` ↔ `data`）。全部以代码为准。

## 1. HTTP 接口

鉴权：除公开路径外，所有请求必须带 `Authorization: Bearer <APP_API_TOKEN>`，否则 401 `{"error":"unauthorized"}`（UTF-8）。公开路径见 `app/.../security/SecurityConfig.java:20-33`。

审计：除 `/api/metrics`（监控页 3 秒轮询，避免刷屏）外，每个 `/api/**` 请求由 `AuditLogFilter` 写一行 JSON 到 `var/logs/audit.log`（方法、路径、状态、耗时、来源 IP、鉴权结果、是否异步），不记录请求体、查询串和 `Authorization` 头。

| 方法 | 路径 | 控制器 | 说明 |
| --- | --- | --- | --- |
| GET | `/health` | `HealthController` | `text/plain` 返回 `ok`，免鉴权 |
| GET | `/`、`/index.html`、`/app.js`、`/styles.css`、`/boot.js`、`/assets/**` | 静态资源 | 由 `spring.web.resources.static-locations=file:./frontend/` 托管 |
| POST | `/api/ask` | `AskController` | 提问，SSE 流；`Content-Type: text/event-stream` |
| GET | `/api/conversations?query=` | `ConversationController` | 最近会话，固定上限 `RECENT_LIMIT=50` |
| GET | `/api/conversations/{id}` | `ConversationController` | 会话详情 + 消息 + 上下文占用 |
| GET | `/api/conversations/{id}/evidence?source_id=` | `ConversationController` | 按来源 id 回取工具原始数据；缺 `source_id` → 400 `{"error":"missing source_id"}` |
| GET | `/api/conversations/{id}/runs/{runId}` | `ConversationController` | 运行原文；`runId` 必须匹配 `[0-9A-Za-z_-]{8,64}`，响应 `Cache-Control: no-store` |
| POST | `/api/conversations/{id}/delete` | `ConversationController` | 删除会话：消息 → 运行文件 → Agent 会话 |
| GET | `/api/research/companies?query=` | `ResearchController` | 研究档案公司列表 |
| GET | `/api/research/companies/{code}?section=&limit=&offset=&status=` | `ResearchController` | 档案详情/分页；`code` 必须 `\d{6}` 否则 404；未知 `section` → 400 `{"error":"unknown section"}`；`limit` 夹到 1..500，默认 200 |
| GET | `/api/metrics` | `MetricsController` | 系统 CPU/内存 + 各工具调用次数、成功率、平均/P95/P99；进程内存、启动以来，缓存命中不计入 |

会话不存在时统一 404 `{"error":"not found"}`。

### `GET /api/metrics`

返回 `system` 与 `tools` 两段：`system` 含 `cpu`、`processCpu`（均 0..1，-1 表示 JDK 暂不可用）、`cores`、`heapUsed`、`heapMax`、`nonHeapUsed`、`memoryUsed`、`memoryTotal`、`uptimeMs`、`threads`（字节/毫秒/个数）；`tools[]` 含 `name`、`count`、`successes`、`successRate`、`lastMs`、`avgMs`、`p95Ms`、`p99Ms`。统计只覆盖真实执行（缓存命中不经过 `ToolRegistry.run`），百分位取每个工具最近 500 次样本，进程重启清零。

### `POST /api/ask`

请求体：

```json
{ "conversation_id": 1, "question": "东鹏饮料靠什么赚钱" }
```

- `conversation_id` 可省略/null/0，表示新会话。
- `question` 为空白 → 400 `{"error":"empty question"}`（普通 JSON，不是 SSE）。
- 响应头：`Cache-Control: no-cache`、`X-Accel-Buffering: no`（禁止 Nginx 缓冲）。
- 超时：`SseEmitter` 10 分钟（`AskController.TIMEOUT_MS`）。超时只结束 emitter，不取消后台任务。
- 线程：实际工作跑在 `askExecutor`（虚拟线程池，`ApiConfig`）。
- 未配置 `DEEPSEEK_API_KEY` 时，引擎是 `UnavailableEngine`，直接推一条 `{"type":"error","text":"助手不可用：请先设置环境变量 DEEPSEEK_API_KEY"}`，不建会话。

## 2. SSE 协议

服务端在 `AskService.ask` 中按顺序发事件，`SseWriter` 序列化为 `data: <json>\n\n`。前端 `frontend/app.js` 按 `\n\n` 切帧、解析 `data:`。

| type | 字段 | 何时发 |
| --- | --- | --- |
| `warning` | `text` | 没识别出公司；只此一条，连接随后结束 |
| `state` | `conversation_id`、`code`、`name`、`notice` | 路由完成后；`notice` 仅在切换公司/大盘时非空：「已切换到 X（code），开始新对话。」 |
| `meter` | `usage` | 初始一次；流式文本每 ≥400ms 一次；`discard`/`tool` 后各一次 |
| `text` | `delta` | 模型流式输出片段 |
| `discard` | — | 模型开始调工具时，丢弃本轮已流出的草稿文本 |
| `tool` | `name`、`company`、`ok`、`cached`、`duration_ms` | 每次工具执行结束 |
| `done` | `conversation_id`、`title`、`message`、`usage` | 成功收尾 |
| `error` | `text`、`conversation_id`、`run` | 引擎抛异常；`text` 形如「研究失败：<message>」 |

`usage` 结构（`ContextMeter.usage`）：

```json
{ "tokens": 1234, "budget_tokens": 55000, "hard_tokens": 80000,
  "percent": 2.2, "ratio": 0.0224, "level": "normal" }
```

`level`：`normal`（<80%）、`warn`（≥80%）、`over`（≥100%，按 55k 预算算）。

`done.message` 结构：

```json
{ "role": "assistant", "text": "...", "sources": [...], "trace": [...],
  "issues": [...], "run": {"id": "20261031T111246-0da539f0", "file_bytes": 4321} }
```

- `sources[i]`：`id`、`label`、`detail`、`as_of`、`fetched_at`、`url`。
- `trace[i]`：`round`、`name`、`arguments`、`ok`、`company`、`as_of`、`error`、`sources`、`cached`、`duration_ms`。
- `issues` 只放前 2 条且跳过空串（Python `[:2]` 后过滤）。
- 落库版本额外带 `raw`（工具消息原文），SSE 不下发。

客户端断连：`AskService` 的 `Sink` 吞掉 `send/complete` 异常，回答照常生成并落库，只是不再推送（`AskServiceTest.sink_that_throws_on_send_still_persists_answer`）。

## 3. 工具契约

### 3.1 `ToolResult`

`core/.../domain/ToolResult.java`：

```java
record ToolResult(boolean ok, String company, String asOf, List<Source> sources,
                  Map<String,Object> data, String error, boolean cached)
```

工厂方法：`ok(company, asOf, sources, data)`、`failure(company, error)`、`failure(company, error, data)`、`asCached()`。序列化用 `toMessageJson()`：省略空值，`cached=true` 时附 `note="本次会话已取过相同参数的数据，未重新请求"`。

### 3.2 工具实现

```java
interface ResearchTool {
    ToolSchema schema();
    ToolResult execute(Map<String,Object> arguments, String defaultCompany);
}
```

- schema 单一来源：`core/src/main/resources/tool-schemas.json`，由 `tools/golden/gen_golden.py` 从 Python `assistant.tools.schemas()` 生成，**禁止手改**。唯一例外是 Python 侧不存在的 Java 新工具 `web_search`（第 34 条），只能手工追加，`ToolSchemasTest` 里有注明。
- 前 32 个的注册顺序与 Python 完全一致，由 `ToolSchemaParityTest` 校验：`resolve_company`、`get_company_profile`、`get_finance`、`get_financial_history`、`get_segments`、`get_management`、`get_shareholders`、`get_peers`、`get_dividends`、`get_insider_trades`、`get_buybacks`、`get_lockup_expiries`、`get_pledges`、`get_earnings_forecasts`、`get_institutional_research`、`get_research_notes`、`get_analyst_views`、`get_announcements`、`get_valuation`、`calc_valuation`、`get_market_overview`、`get_news_events`、`get_sector_rotation`、`screen_candidates`、`build_strategy_plan`、`save_daily_judgment`、`review_research_reports`、`list_research_memory`、`read_research_memory`、`append_research_record`、`update_research_record`、`record_conclusion_change`（32 个）。第 33 个是 Java 新增的 `web_search`，追加在末尾。
- `load_skill` 只是 `tool-schemas.json` 里的 schema，没有 Java 执行类；实际执行由 AgentScope 的 `SkillLoadTool` 提供，模型侧名字是 `load_skill_through_path`（`AgentEngine` 的 `UNCACHED_TOOLS` 仍按 Python 名 `load_skill` 屏蔽缓存）。
- `web_search`：`query` 必填、`count` 默认 5 钳到 1..10；百度千帆优先（`BAIDU_API_KEY`），任何失败回落 DeepSeek Search（`DEEPSEEK_API_KEY`）。结果归一成 `{title,url,snippet,content,date,site}`，每条生成 `web:<query-hash>:<n>` 的可引用 source；`content` 截断到 1000 字。`web_search` 在 `UNCACHED_TOOLS` 里。前端靠 source id 的 `web:` 前缀把这类引用标成「网页」（来源列表、悬停卡、来源详情侧栏），点正文角标直接新标签打开原文；改前缀会丢标记和跳转。

### 3.3 参数与钳位

| 工具 | 参数（* = 必填） | 默认/钳位 |
| --- | --- | --- |
| `resolve_company` | `name_or_code*` | — |
| `get_company_profile` | `company` | — |
| `get_finance` | `company` | — |
| `get_financial_history` | `years`, `company` | 10，1..10 |
| `get_segments` | `years`, `company` | 5，1..10 |
| `get_management` | `resume_chars`, `company` | 300，0..2000 |
| `get_shareholders` | `periods`, `company` | 8，1..10 |
| `get_peers` | `limit`, `company` | 8，3..20 |
| `get_dividends` | `years`, `company` | 10，1..20 |
| `get_insider_trades` | `years`, `company` | 3，1..10 |
| `get_buybacks` | `years`, `company` | 5，1..10 |
| `get_lockup_expiries` | `years`, `ahead_months`, `company` | 3，1..10；12，0..36 |
| `get_pledges` | `years`, `company` | 3，1..10 |
| `get_earnings_forecasts` | `years`, `company` | 3，1..10 |
| `get_institutional_research` | `years`, `company` | 3，1..10 |
| `get_research_notes` | `events`, `years`, `max_chars`, `company` | 3，1..10；3，1..10；12000，3000..40000 |
| `get_analyst_views` | `days`, `company` | 365，30..730 |
| `get_announcements` | `days`, `company` | 180，1..365 |
| `get_valuation` | `company` | — |
| `calc_valuation` | `mode*`（check/scenario/terminal）+ `price`、`base_value`、`base_label`、`growth_pct[3]`、`exit_pe[3]`、`years`、`roic_pct`、`terminal_g_pct`、`discount_rate_pct`、`company` | scenario 需要 `base_value` + 恰好 3 个 `growth_pct`/`exit_pe`；`years` 默认 3，1..10 |
| `get_market_overview` | — | — |
| `get_news_events` | `limit` | 12，1..20 |
| `get_sector_rotation` | `limit` | 8，3..20 |
| `screen_candidates` | `limit`, `min_amount_yi` | 15，3..30；1.0，0.1..50 |
| `build_strategy_plan` | `market_state`、`risk_level`、`risk_tolerance`、`position_pct`、`cash_pct`、`holdings` | — |
| `save_daily_judgment` | `strategy*`（object） | — |
| `review_research_reports` | `horizon`, `limit` | 5，1..20；20，1..60 |
| `list_research_memory` | `company` | — |
| `read_research_memory` | `section*`, `company`、`record_id`、`status`、`limit`、`offset` | limit 200，offset 0 |
| `append_research_record` | `section*`, `record*`, `company` | — |
| `update_research_record` | `section*`, `record_id*`, `patch*`, `company` | — |
| `record_conclusion_change` | `company`、`conclusion_id*`、`operation*`、`old_conclusion*`、`new_conclusion*`、`new_title`、`trigger_event*`、`trigger_date*`、`evidence_ids*`、`reason*`、`impact*`、`review_status`、`confidence`、`based_on_fact_ids`、`based_on_hypothesis_ids` | — |
| `web_search` | `query*`、`count` | 5，1..10 |

公司参数由 `CompanyTool` 统一处理：未传时用会话默认公司（大盘会话为空串）；解析失败返回 `ToolResult.failure`，文案如「没有找到「X」」。

### 3.4 错误语义

- 未知工具：`ToolResult.failure(null, "未知工具：" + name)`（先做 company 类型校验）。
- 工具抛 `RuntimeException`：`ToolResult.failure(null, message)`。
- 工具参数校验失败（流程层）：`AgentEngine` 补一条「工具调用失败：参数校验未通过或工具未执行」的 trace 与 stage。

## 4. 端口契约（`core.port`）

`core` 只定义接口，`data`/`app` 提供实现（见 `MODULE_DETAILS.md`）。要点：

| 端口 | 关键方法 | 说明 |
| --- | --- | --- |
| `CompanyDirectory` | `byCode`、`byName`、`all` | 公司目录，路由与工具共用 |
| `ConversationRepository` | `create`、`find`、`recent`、`messages`、`append`、`updateUsage`、`delete` | 会话持久化 |
| `RunStore` | `write`、`read`、`deleteAll` | 运行原文 |
| `CompanyProfilePort` / `FinancePort` / `FinancialHistoryPort` / `SegmentPort` / `PeerPort` / `ValuationPort` / `AnnouncementPort` | 各一个 `ToolResult xxx(String code, …)` | 阶段 1 数据 |
| `ManagementPort` / `ShareholderPort` / `DividendPort` / `InsiderTradePort` / `BuybackPort` / `LockupPort` / `PledgePort` / `ForecastPort` / `ResearchPort` / `ResearchNotesPort` / `AnalystViewPort` | 同上 | 阶段 2 数据 |
| `MarketPort` / `NewsPort` / `SectorPort` / `ScreenPort` / `StrategyPort` / `ReviewPort` / `ValuationCheckPort` | 大盘/新闻/板块/筛选/策略/复盘 | 阶段 3 数据 |
| `ResearchMemoryPort` | `companies`、`company`、`detail`、`read`、`append`、`update`、`change` | 研究档案 |
| `SearchPort` | `search(query, count)` | 联网搜索：百度优先、DeepSeek 回落 |

端口统一返回 `ToolResult`，异常在适配器内转成 `failure`，不向 Agent 外抛（`ToolRegistry` 兜底除外）。
