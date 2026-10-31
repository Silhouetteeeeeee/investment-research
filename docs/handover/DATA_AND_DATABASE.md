# 数据与数据库

## 1. 存储总览

| 存储 | 路径 | 内容 | 清理/保留 |
| --- | --- | --- | --- |
| SQLite | `${APP_DATA_DIR:./var}/app.db` | 32 张业务表 + Flyway 历史（共 34 张） | 长期；每日备份保留 7 份 |
| 运行原文 | `${APP_DATA_DIR}/runs/<run_id>.json` | 一次问答的时间线、阶段、问题、模型 | 保留最新 200 份（按 mtime 剪枝） |
| Agent 会话状态 | `${APP_DATA_DIR}/sessions/default/**` | AgentScope `JsonFileAgentStateStore` | 删除会话时一起删 |
| Agent 工作区 | `${APP_DATA_DIR}/workspace/` | HarnessAgent 工作区 | 不清理 |
| 进程内缓存 | 各适配器 `ConcurrentHashMap` | 行情/板块/筛选/复盘的短 TTL 结果 | 重启即失；见 §5 |
| 工具结果缓存 | `core.agent.ToolCallCache` | 按会话的 `ToolResult` | 删会话时清，另有 500 会话 / 200 条每会话 / 30 分钟 TTL 上限；17 个时效性工具不入缓存 |

连接：`jdbc:sqlite:file:${APP_DATA_DIR:./var}/app.db?busy_timeout=5000`，Hikari `maximum-pool-size: 4`。单写者模型，写并发靠 `busy_timeout` + 业务层串行（`ResearchMemoryStore.append` 为 `synchronized`）。

## 2. Flyway 迁移（`data/src/main/resources/db/migration/sqlite`）

| 版本 | 文件 | 表 | 关键列/约束 |
| --- | --- | --- | --- |
| V1 | `V1__conversation.sql` | `conversation`、`message` | `conversation(id INTEGER PK AUTOINCREMENT, title, primary_code, primary_name, companies, usage_json, created_at, updated_at)`；`message(conversation_id, seq, role, payload_json, created_at, PK(conversation_id,seq))`；索引 `idx_message_conversation` |
| V2 | `V2__stock.sql` | `stock` | `code PK, name NOT NULL, refreshed_at NOT NULL` |
| V3 | `V3__company_profile.sql` | `company_profile` | `code PK, name, industry, profile, scope, employees, fetched_at` |
| V4 | `V4__biz_segment.sql` | `biz_segment` | PK `(code, report_date, kind, item_name)`；`income/share/cost/profit/gross_margin/rank/fetched_at` |
| V5 | `V5__fin_tables.sql` | `fin_balance`、`fin_cashflow`、`fin_income`、`fin_metrics` | 前三张 PK `(code, report_date)`，带 `ann_date/currency/audited/statement_type/source/fetched_at`；`fin_metrics` PK `(code, report_date)` + `computed_at` |
| V6 | `V6__peers_valuation.sql` | `company_peers`、`val_history`、`val_peer` | `company_peers(code PK, data, fetched_at)`；`val_history` PK `(code,trade_date)`；`val_peer` PK `(board_code,trade_date,code)` |
| V7 | `V7__announcement.sql` | `announcement` | PK `(code, art_code)`；`notice_date/title/categories/url/fetched_at` |
| V8 | `V8__fin_disclosure.sql` | `fin_disclosure` | PK `(code, report_period)`；`first_scheduled/change_1..3/actual_date/fetched_at` |
| V9 | `V9__research_cache.sql` | 11 张缓存表 | 8 张同形表 `company_management/shareholders/dividends/insider_trades/buybacks/lockups/pledges/forecasts`（`code PK, data, fetched_at`）；`company_research(code,years PK,data,fetched_at)`；`research_notes(code,note_key PK,content,fetched_at)`；`company_reports(code,days PK,data,fetched_at)` |
| V10 | `V10__research_memory.sql` | `company_memory`、`memory_fact`、`memory_hypothesis`、`memory_conclusion`、`memory_question`、`memory_change` | 每张记忆表 `id` 自增 + `UNIQUE(code, <x>_id)`；`memory_fact` 另 `UNIQUE(code, dedupe_key)`；`memory_change` 外键指向 `memory_conclusion(code,conclusion_id)`；索引若干；触发器 `memory_change_no_update` / `memory_change_no_delete` |
| V11 | `V11__daily_reports.sql` | `daily_reports` | `trade_date PK, saved_at, payload` |

设计文档要求「SQLite / MySQL 双方言目录」，**目前只有 `sqlite` 目录**，MySQL 迁移与 Testcontainers 属阶段 4（未实现）。

## 3. 各表由谁写、由谁读

| 表 | 写入者 | 读取者 |
| --- | --- | --- |
| `conversation` / `message` | `ConversationRepositoryImpl`（`AskService` 落用户/助手/错误消息，`updateUsage`） | `ConversationController`、`AskService`（路由读 `primaryCode`/`primaryName`）、`ContextMeter` |
| `stock` | `StockMasterSync`（每日 06:00，首次懒加载也会触发） | `StockDirectoryAdapter`、`PeerAdapter`、`ValuationAdapter` |
| `company_profile` | `CompanyProfileAdapter`（TTL 30 天，delete+insert） | 同上适配器 |
| `biz_segment` | `SegmentAdapter`（按报告期新鲜度） | 同上 |
| `fin_balance/income/cashflow/metrics` | `FinanceAdapter`（一个事务内替换四张表） | `FinanceAdapter`、`FinancialHistoryAdapter`、`DividendAdapter`、`CalcValuationAdapter` |
| `fin_disclosure` | **无写入者**（`FinanceAdapter.java:46` 注释：「fin_disclosure 暂时没人写」） | `FinanceAdapter` 的披露触发刷新逻辑（因此目前不生效） |
| `company_peers` | `PeerAdapter` | `PeerAdapter` |
| `val_history` / `val_peer` | `ValuationSupport`（复合主键，delete+insert 事务） | `ValuationAdapter`、`PeerAdapter`、`CalcValuationAdapter` |
| `announcement` | `AnnouncementAdapter`（upsert + 超 400 天删除） | `AnnouncementAdapter` |
| V9 缓存表 | 对应适配器经 `ResearchCacheStore` 写入 `data` JSON | 同适配器 |
| `research_notes` | `ResearchNotesAdapter` | 同适配器 |
| `memory_*` / `company_memory` | `ResearchMemoryStore`（`append` 只增，`update` 白名单，`change` 只增） | `ResearchMemoryStore`、`ResearchController`、记忆工具 |
| `daily_reports` | `DailyReportStore`（`save` 按 `trade_date` upsert） | `DailyReportStore.load`、复盘工具 |

## 4. 读写路径

### 4.1 按需抓取（典型）

```text
工具 → 端口 → 适配器的 ensure(store, cache, code, key, maxAgeHours, clock, fetch)
  1) 读缓存表：够新 → 直接用（返回 cached/stale 标记）
  2) 过期/缺失 → 调上游 → 解析 → 写回缓存
  3) 上游失败且有过期缓存 → 返回 stale 数据 + 错误说明
  4) 上游失败且无缓存 → ToolResult.failure(...)
```

`DatacenterSupport.ensure` 是这条路径的通用实现；F10/财报/公告/估值各有自己的新鲜度规则。

### 4.2 缓存 TTL 一览

| 数据 | TTL / 判据 | 代码 |
| --- | --- | --- |
| 股票主表 | 每日 06:00 + 空表懒加载（失败后 60s 冷却） | `StockMasterSync`、`StockDirectoryAdapter` |
| 公司概况 | 30 天 | `CompanyProfileAdapter.MAX_AGE_DAYS=30` |
| 主营构成 | 按预期报告期（4/30、8/31、10/31、次年 4/30）+ 落后口径 24h | `SegmentAdapter` |
| 财报三表/指标 | 7 天；某表为空时 30 分钟重试 | `FinanceAdapter.DEFAULT_CACHE_DAYS=7` |
| 公告 | 6 小时，上游保留 400 天 | `AnnouncementAdapter` |
| 同业 | 6 小时 | `PeerAdapter` / `EastmoneySupport.MAX_AGE_HOURS=6` |
| 估值历史/同业 | 6 小时 | `ValuationSupport` |
| 管理层 | 7 天（168h） | `ManagementAdapter` |
| 股东/分红/内部人/回购/解禁/质押/预告/调研 | 24 小时 | 各适配器 |
| 研报观点 | 24 小时 | `AnalystViewAdapter` |
| 调研纪要 | 30 天 | `ResearchNotesAdapter.NOTE_MAX_AGE_HOURS=720` |
| 大盘/新闻/板块/筛选/复盘（进程内） | 板块 600s、筛选 900s、复盘 900s | `SectorAdapter`、`ScreenAdapter`、`ReviewAdapter` |
| 工具结果（会话内） | 30 分钟 TTL，每会话最多 200 条、最多 500 个会话，LRU 淘汰；17 个时效工具不入缓存 | `ToolCallCache`、`AgentEngine.UNCACHED_TOOLS` |

## 5. 数据源矩阵

| 数据 | 上游 | 适配器 |
| --- | --- | --- |
| 股票主表 | 东财 `push2test.../clist/get`（可由 `app.stock-list-url` 改） | `StockMasterSync` |
| 公司概况 / 主营构成 | 东财 F10 `emweb.securities.eastmoney.com/PC_HSF10/*` | `CompanyProfileAdapter`、`SegmentAdapter` |
| 财报三表 | 新浪 `quotes.sina.cn` `CompanyFinanceService.getFinanceReport2022` | `FinanceAdapter` |
| 公告 | 东财 `np-anotice-stock` | `AnnouncementAdapter` |
| 分红/回购/解禁/质押/内部人/预告/调研 | 东财 `datacenter-web.eastmoney.com/api/data/v1/get`（各 `RPT_*`） | 各 `*Adapter` |
| 研报 | 东财 `reportapi.eastmoney.com/report/list` | `AnalystViewAdapter` |
| 管理层 | 同花顺 `basic.10jqka.com.cn/mapp/...` | `ManagementAdapter` |
| 股东 | 东财 F10 `ShareholderResearch/PageAjax` | `ShareholderAdapter` |
| 实时行情/指数 | 新浪 `hq.sinajs.cn` | `SinaIndexSource`、`MarketAdapter` |
| 全市场快照 | 新浪 `Market_Center.getHQNodeData`（100/页，最多 80 页） | `SinaAShareSpotSource` |
| 个股/指数日线 | 东财 `push2his`（不复权）、腾讯 `ifzq.gtimg.cn` | `EmStockDailySource`、`TencentTrendSource` |
| 涨跌家数 | 乐咕 `legulegu.com/stockdata/market-activity` | `LeguBreadthSource` |
| 涨停池 | 东财 `push2ex` | `LimitUpSource` |
| 行业板块 | 同花顺 `q.10jqka.com.cn` 页面分页（GBK，最多 60 页） | `ThsSectorSource` |
| 板块日线 | 同花顺 `d.10jqka.com.cn/v6/line/bk_<code>/01/last.js` | `ThsBoardHistorySource` |
| 快讯/新闻 | 财联社 `cls.cn`（签名）、东财 `np-weblist`、央视 `tv.cctv.com` | `ClsNewsSource`、`EmNewsSource`、`CctvNewsSource` |
| 海外指数 | 腾讯美股 K 线、新浪港股（GB18030） | `OverseasMarketSource` |

统一封装：`RestClientUpstreamClient` —— 直连（`ProxySelector.of(null)`）、Chrome UA、连接/读取 60s、最多 2 次尝试（仅 IOException 与 5xx 重试，4xx 不重试）、全局并发 4、退避 500ms。异常统一为 `UpstreamException(status, url)`，message 里的查询串会被去掉。

## 6. 研究档案

- 表：`company_memory` + 五张业务表（facts / hypotheses / conclusions / questions / changes）。
- 分节读取：`ResearchMemoryStore.read(code, section, recordId, status, limit, offset)`；未知 section 抛 `MemoryException("未知模块：…")`。
- 写入规则在 `core.research.MemoryRules`：枚举白名单、字段字节上限（单字段 20KB、单记录 50KB）、`dedupe_key` 去重、`memory_change` 只增。
- 旧库导入：`MemoryImportRunner` 读 `APP_MEMORY_IMPORT`，仅当档案为空且路径是文件时调用 `importLegacy`，用 `INSERT OR IGNORE` 逐行导入，保留 `code`、业务 id、时间戳；返回导入行数并打日志。再次导入时非空 → 跳过。
- 验收目标（设计文档）：5 家公司 / 17 事实 / 2 假设 / 3 结论 / 6 问题 / 1 变更，内容一致。`ResearchMemoryStoreTest.import_keeps_python_counts_business_ids_and_timestamps` 断言 34 行导入与 id/时间戳保留。

## 7. 运行原文（`var/runs/<run_id>.json`）

`run_id` 格式 `uuuuMMdd'T'HHmmss-` + 8 位十六进制，例如 `20261031T111246-0da539f0`。

字段（`RunRecorder`）：`version=1`、`run_id`、`conversation_id`、`question`、`model`、`started_at`、`finished_at`、`total_ms`、`rounds`、`max_rounds`、`stop_reason`（`answered` / `rounds_exhausted` / `error`）、`error`、`issues[]`、`stages[]`、`prompts[]`、`skills[]`、`tool_schemas`、`calls[]`。

`stages[]`：

- 模型：`kind:"model"`、`start_ms`、`duration_ms`、`round`、`forced`、`ttft_ms`、`prompt_tokens`、`completion_tokens`、`finish_reason`、`usage_source`（`api` / `estimate`）。
- 工具：`kind:"tool"`、`name`、`arguments`、`ok`、`cached`、`error`、`company`、`as_of`、`sources[]`、`data_chars`。

写入：先写临时文件 `.<run_id>.<pid>.tmp` 再原子移动；读取时校验 id 正则 `[0-9A-Za-z_-]{8,64}` 且不含 `..`。`prompts`/`skills`/`calls` 目前保留为空数组（已知差异）。

## 8. 备份与恢复

- `scripts/backup.sh`：`sqlite3 .backup` → `$DATA_DIR/backups/app-YYYYMMDD.db`，保留 7 份，`umask 077`。
- 恢复：停应用 → 覆盖 `$DATA_DIR/app.db` → 启动（Flyway 校验版本）。
- 运行原文不在备份脚本内；需要保留时自行打包 `var/runs`。

## 9. 本机开发库现状（`var/app.db`，仅供参考）

34 张表 = 32 张业务表 + `flyway_schema_history` + `sqlite_sequence`。有数据的表：`stock` 5563、`val_history` 3456、`biz_segment` 400、`announcement` 218、`fin_balance` 88、`fin_income`/`fin_cashflow`/`fin_metrics` 各 90、`val_peer` 86、`message` 6、`conversation` 3、`company_profile` 1、`company_peers` 2、`company_memory` 1、`memory_fact` 3、`memory_conclusion` 1、`memory_question` 1；V9 缓存表与 `daily_reports`、`fin_disclosure` 为空。这只反映本机试跑，不是验收基线。
