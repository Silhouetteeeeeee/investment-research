# 测试与质量

## 1. 怎么跑

```bash
export JAVA_HOME=/c/Users/dudu/.jdks/ms-21.0.10    # 本机已有的 JDK 21
./mvnw verify                # 编译 + 全部测试 + 打包
./mvnw -q spotless:check     # 格式检查
./mvnw -pl data -am test     # 单模块（带 -am，否则用本地旧 core jar）
./mvnw -o ...                # 离线（依赖已缓存）
```

Windows 终端输出可能是 GBK，重定向到文件后 `iconv -f gbk -t utf-8` 再读。

最近一次完整结果（阶段 3 收尾 + 评审修复后，见 `.superpowers/sdd/2026-10-31-java-migration-phase3/progress.md`）：`./mvnw verify` 通过，**core 121 / data 119 / app 54**；`spotless:check` 退出 0。测试数为执行数，参数化用例会展开，与 `@Test` 注解计数不完全一致。

## 2. 测试分层

| 层 | 位置 | 做法 |
| --- | --- | --- |
| 纯单元 | `core/src/test`: `citation`、`research`、`valuation`、`service`、`tool`、`run` | 无 Spring、无网络 |
| 金标准对拍 | `core/src/test/resources/golden/**` | 用 Python 版在冻结数据上生成的 JSON，逐字段比较（`ToolResultTest`、`ToolSchemaParityTest`、`ResearchScoringTest`、`SectorRotationParityTest`） |
| 上游契约 | `data/src/test` | WireMock 回放录制响应；断言解析结果与 golden 工具输出一致（只忽略 `sources[].fetched_at`） |
| 持久化 | `data/src/test`: `persistence`、`memory` | 临时 SQLite + Flyway，真库读写 |
| HTTP/SSE | `app/src/test`: `api`、`security` | `MockMvc` / 真实端口；断言事件序列、鉴权、静态资源 |
| 端到端 | `app/src/test/e2e/AskFlowTest` | `ScriptedModel` 假模型 + 真 Spring 上下文 + 真 SQLite，跑完整 `/api/ask` |
| 架构 | `app/src/test/.../ModuleBoundaryTest` | ArchUnit 四条规则（见 `ARCHITECTURE.md` §1） |

## 3. 测试清单

### core（16 类 + 1 个假模型，121 个用例）

| 测试类 | 用例数 | 覆盖 |
| --- | --- | --- |
| `agent/AgentEngineTest` | 14 | 流式顺序、大盘无默认公司、运行记录落盘、skill 加载、引用清洗、会话复用、缓存/不缓存、未知工具、轮次用尽、工具异常、参数校验失败 trace、失败运行记录 |
| `agent/ToolCallCacheTest` | 7 | 会话隔离、key 规范化、cached 标记、清理范围、每会话 LRU 容量、会话数上限、TTL 过期 |
| `citation/CitationValidatorTest` | 16 | 已知/未知/重复 id、无引用数字、嵌套、裁剪、大小写、Unicode 数字、null |
| `domain/ToolResultTest` | 6 | 字段省略、cached note、失败形状、Python 数值/字符串表示、工具消息金标准 |
| `research/ResearchScoringTest` | 3 | 筛选/策略/复盘金标准 |
| `research/SectorRotationParityTest` | 1 | 板块统计/打分/驱动因素金标准 |
| `run/FileRunStoreTest` | 4 | 原子写读、非法 id、保留 200 份、批量删除 |
| `run/RunRecorderTest` | 7 | 阶段顺序、强制作答、错误保留、usage 来源、收尾字段、工具 stage、null ttft |
| `service/AskServiceTest` | 13 | 事件顺序、换公司、只警告、大盘会话、失败落库/运行、空消息、断连仍落库、用量、标题截断、大盘不动原会话、运行写失败 |
| `service/CompanyResolverTest` | 7 | 代码形式、精确名、默认公司、空输入、候选、未找到、错误前缀 |
| `service/ContextMeterTest` | 8 | 环形状/固定成本、估算、消息+草稿、raw 内容、无 content 的 map、阈值、比例钳位、负草稿 |
| `service/QuestionRouterTest` | 11 | 代码/名称优先级、同位置择长、大盘 vs 公司、指代保留、切换、不重复切换、纯文本保留 |
| `tool/ToolRegistryTest` | 17 | schema 顺序、未知工具、默认公司/显式公司/解析失败、端口异常、非字符串 company、null 参数、重复/null 工具、参数钳位 |
| `tool/ToolSchemaParityTest` | 2 | 与 Python schema 逐字段一致；注册顺序一致 |
| `tool/ToolSchemasTest` | 2 | 33 条 schema 可加载；未知名称抛错 |
| `valuation/ValuationMathTest` | 3 | 非法增长/ROIC、Decimal 算术参照 |

`ScriptedModel`（无 `@Test`）是测试用假模型：按脚本返回文本/工具调用，并记录收到的消息，供 Agent 循环类测试使用。

### data（28 类，119 个执行用例）

| 测试类 | 用例数 | 覆盖 |
| --- | --- | --- |
| `StockDirectoryAdapterTest` | 11 | 代码/名称解析、前后缀、懒加载、幂等刷新、畸形/空 200/WAF 响应、60s 冷却、3 个 golden |
| `eastmoney/AnnouncementAdapterTest` | 11 | 2 golden、天数钳位/排序、空/新鲜空失败、截断、5xx 缓存回退、坏时间戳、坏日期行、缓存/重抓页停止、回滚、迁移列一致性 |
| `eastmoney/ValuationAdapterTest` | 5 | 2 golden、上一交易日回退、取数/落库列、重复 key、空 payload 失败 |
| `eastmoney/PeerAdapterTest` | 3 | 2 golden、排名/中位数稳定、迁移列一致性 |
| `eastmoney/DividendAdapterTest` | 3 | golden、无缓存回退上游错误、空分红行 |
| `eastmoney/{AnalystView,Buyback,Forecast,InsiderTrade,Lockup,Management,Pledge,Research,ResearchNotes,Shareholder}AdapterTest` | 各 1（10 个类） | 各自 golden 回放 |
| `eastmoney/CalcValuationGoldenTest` | 1 | 三种 mode 对 golden |
| `f10/CompanyProfileAdapterTest` | 5 | 5 个 golden、HTML/空失败、null 字段替换、30 天过期、迁移列一致性 |
| `f10/SegmentAdapterTest` | 8 | 5/3 年 golden、历史顺序、HTML 失败、落后口径 24h、回滚、行业回退、仅地区缓存、报告期截止日参数化 |
| `finance/FinanceAdapterTest` | 10 | 5 个 golden、短趋势窗口、披露触发刷新、空表 30 分钟重试、迁移一致性、部分缺表、HTML 失败、幂等、回滚、指标一致性 |
| `finance/FinancialHistoryAdapterTest` | 1 | 默认与 5 年 golden |
| `market/MarketAdapterTest` | 12 | 新浪指数、腾讯手数、乐咕、同花顺板块、涨停、财联社时间、分类 golden、overview/as_of、时钟 session、部分失败、全失败、端口委派 |
| `market/NewsSectorAdapterTest` | 4 | 新闻 golden、央视清洗、缺源容忍、板块排名/缺历史 |
| `memory/ResearchMemoryStoreTest` | 5 | 旧库导入计数/id/时间戳、幂等导入、重复事实、并发重复事实、未知 section/缺公司 |
| `persistence/ConversationRepositoryTest` | 6 | 往返、搜索/排序、有序消息扁平化、级联删除、坏 payload 跳过、新库 schema |
| `persistence/DailyReportStoreTest` | 4 | 同日覆盖、最新优先、缺 trade_date 拒绝、坏 payload 跳过 |
| `persistence/ResearchCacheStoreTest` | 3 | 只按 code 的往返/覆盖、带 key 表、notes key |
| `upstream/RecordedFixtureTest` | 2 | 118 个录制映射可回放解码；56 页股票列表覆盖全 A 股 |
| `upstream/UpstreamClientTest` | 7 | 字符集、500 重试、404 不重试、查询串/头脱敏、信号量不死锁、UTF-8 默认 |

### app（15 类，54 个用例）

| 测试类 | 用例数 | 覆盖 |
| --- | --- | --- |
| `api/ConversationControllerTest` | 10 | 列表字段、详情去 raw/trace_count、404、证据最后匹配/null/400、run id 校验/路径穿越/no-store、删除级联 |
| `security/TokenAuthTest` | 9 | 401 JSON、不建会话、错 token、Unicode 头、正确 token、公开静态页/health、管理端口 health、空白/非 ASCII 启动失败 |
| `e2e/AskFlowTest` | 5 | 完整 SSE + 落库 + 回放；重启后读回；运行详情工具阶段；管理层 skill→工具；大盘 skill→取数 |
| `api/AskControllerTest` | 4 | 空问题 400、state/text/done + 落库、断连不中断落库、引擎错误带 run 并落库 |
| `api/SseWriterTest` | 4 | 帧格式、中文不转义、JSR-310 ISO、断连吞异常 |
| `api/AuditLogFilterTest` | 4 | 成功请求字段、401 标 `auth=missing/invalid`、异常记录 failure、非 `/api` 不记录 |
| `api/MemoryApiTest` | 4 | 导入公司列表、详情分节/计数、分节分页、坏 section/code |
| `SkillContentTest` | 3 | skill 正文与 Python 快照一致、frontmatter name 与目录一致、引用的工具存在 |
| `api/AskStreamAuthTest` | 2 | 带令牌异步流派 200 全量；无令牌 401 |
| `api/AskControllerUnavailableTest` | 1 | 缺 key 干净报错 |
| `HealthControllerTest` | 1 | `/health` 免令牌 |
| `AssistantWiringTest` | 1 | Bean 齐全、`/api/ask` 接真 `AskService` |
| `AssistantEngineLiveBeanTest` | 1 | 有 key 时建真引擎 |
| `AssistantEngineFallbackTest` | 1 | 缺 key 时 `UnavailableEngine` |
| `ModuleBoundaryTest` | 4 个 `@ArchTest` | `core` 不依赖 data/app、`data` 不依赖 app、`core` 无 Web/MyBatis/JDBC、AgentScope 限于 `core.agent` |

## 4. 夹具与金标准

### 4.1 目录

| 路径 | 内容 | 生成方式 |
| --- | --- | --- |
| `core/src/test/resources/golden/tools/*.json` | 99 个工具输出黄金（`<tool>__<code>__<args-hash>.json`） | `tools/golden/gen_golden.py` |
| `core/src/test/resources/golden/rows/*.json` | 102 个数据库行黄金 | 同上 |
| `core/src/test/resources/golden/{tool-messages,screening,strategy,review,sector_rotation,market_state,news_events}.json` | 结构/场景金标准 | 同上 |
| `core/src/main/resources/tool-schemas.json` | 33 条工具 schema | 同上（**生成物，不改手写**） |
| `core/src/test/resources/fixtures/stock-snapshot.db` | 冻结行情/财报库（5 家公司） | 同上 |
| `core/src/test/resources/fixtures/research-memory.db` | 旧研究档案样本 | 从 Python 库复制 |
| `core/src/test/resources/skills-python/*.md` | 14 个 Python skill 原文（`business_model` 无快照） | 从 `assistant/skills.py` 导出 |
| `data/src/test/resources/wiremock/{eastmoney,market,sina}` | 82 / 21 / 15 个映射 | `tools/fixtures/record.py` |

生成脚本需要 Python 仓在 `D:/Programming/PythonProject`（可用 `--repo` 覆盖），会 monkey-patch 网络、强制缓存过期、固定 `GOLDEN_AS_OF="2026-10-30"`。任何工具抛错或 `ok` 不一致时脚本非零退出。

### 4.2 金标准覆盖缺口（README 已记）

- `get_research_notes` 只有 2 家公司。
- `601318` 在 8 张缓存表里没有数据；`600600`/`601606` 缺回购；`600600`/`601318` 缺限售解禁；`601606` 缺内部人交易。
- 换一家公司回放时可能取不到数据——这是快照缺口，不是取数错误。

## 5. 质量门禁

| 门禁 | 命令/机制 | 现状 |
| --- | --- | --- |
| 编译 | `mvn verify` | Java 21，`-Werror` 未启用 |
| 测试 | Surefire 3.5.2 | 全绿 |
| 格式 | Spotless 2.44.2：UNIX 换行、去未用 import、去尾空白、文件末尾换行 | `spotless:check` 通过 |
| 架构 | ArchUnit 1.5.1 | 4 条规则 |
| Schema 一致性 | `ToolSchemaParityTest` | 与 Python 逐字段 |
| CI | 无 `.github/`、无 Jenkinsfile | **没有** |

## 6. 测试风险与缺口

1. **上游只测回放，不测真实网络**：所有适配器验证的是「录下来的响应能解析」，接口改版、反爬、限流都没有自动化兜底。交接后应先跑一次真实链路的 smoke（见下）。
2. **金标准是快照，不是等价证明**：Python 侧行为若有 bug，Java 照抄也算「一致」；反过来 Python 未覆盖的分支，Java 没有对照。
3. **并发只覆盖研究档案写入**：`ResearchMemoryStoreTest.concurrent_appends...`；上游并发、SSE 并发、SQLite 写并发没有压力测试。
4. **无 MySQL 验证**：迁移目录只有 SQLite，设计文档中的 Testcontainers 计划未落地。
5. **前端无自动化测试**：`app.js` 只被 HTTP 层的 `test-web` 占位影响，交互逻辑靠人工。
6. **模型回答不可复现**：`ScriptedModel` 只验证循环与事件，不验证回答质量；设计文档要求人工抽检 3–5 家公司。
7. **`MarketAdapterTest.startStubbed` 重复启动 WireMock 且只停最后一个**（评审已记录为延期 minor），长期跑可能泄漏端口/线程。
8. **Qt/无**：本项目没有 Qt，不存在 Python 仓的 139 退出码问题。

## 7. 建议的交接验证顺序

```bash
export JAVA_HOME=/c/Users/dudu/.jdks/ms-21.0.10
./mvnw verify && ./mvnw spotless:check          # 本地全绿基线
# 起服务（需真实 key 才能测模型）
APP_API_TOKEN=... DEEPSEEK_API_KEY=... java -jar app/target/app-0.1.0-SNAPSHOT.jar
APP_API_TOKEN=... bash scripts/smoke.sh         # 4 项 HTTP 断言
# 浏览器提问「东鹏饮料靠什么赚钱」，检查 [[profile:605499]] 等引用与运行详情
```

真实模型链路**没有自动化覆盖**，必须人工跑一次；离线环境无法代跑。
