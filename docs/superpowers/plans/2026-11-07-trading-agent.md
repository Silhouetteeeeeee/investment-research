# 后台模拟交易 Agent 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在当前应用里增加一个全程自主的 A 股模拟盘交易 Agent：盘前模型写计划，盘中每 10 秒按计划价确定性成交，午盘/收盘模型复盘，并提供能看到完整思考与引用的「交易」页。

**Architecture:** 复用现有 `AgentEngine`（可注入系统提示词、开启完整追踪）跑三个模型阶段；盘前/午盘的计划先由 `SaveTradePlanTool` 暂存，阶段成功后才在事务里提交。盘中执行完全不经过模型：`TradingExecutor` 读 ACTIVE 计划 → `QuotePort` 批量取价 → 触发即成交 → 写订单/持仓/现金/tick。所有状态在 SQLite（V13），历史阶段 payload 存在 `trading_phase`，不依赖 `var/runs` 轮转。

**Tech Stack:** Java 21、Spring Boot 3.5.16、AgentScope-Java 2.0.4、JdbcTemplate + SQLite、Flyway V13、Vue 3 单页前端。

**Spec:** `docs/superpowers/specs/2026-11-07-trading-agent-design.md`

## Global Constraints

- 单实例；不引入多租户、插件系统、工作流引擎、消息队列、新依赖。
- 模块边界：`core` 不依赖 `data`/`app`，不出现 Web/MyBatis/JDBC 类型；`data` 不依赖 `app`；`io.agentscope..` 只允许出现在 `core.agent`。
- 所有外部能力走 `core.port`；工具里不拼 SQL；Controller 里不写业务逻辑。
- 密钥只从环境变量读（`DEEPSEEK_API_KEY`、`BAIDU_API_KEY`、`XQ_TOKEN`、`APP_API_TOKEN`），不写进仓库、日志、错误消息、测试、文档。
- SQLite 单文件 `var/app.db`，Flyway 只新增 V13，不改 V1–V12。
- Python 仓库 `D:\Programming\PythonProject` 与 `data/stock.db` 只读。
- 金额一律用「分」的 `long`；时间列格式 `yyyy-MM-dd'T'HH:mm:ss.SSSSSS`（本地时区）；交易时段与阶段判定固定 `Asia/Shanghai`。
- 代码注释以中文为主，讲「为什么」。
- 每个任务结束时 `./mvnw -q -pl <mod> -am test` 通过；全部完成后 `./mvnw verify` + `./mvnw -q spotless:check` 双绿。
- 提交信息沿用 `feat(area): …` / `fix(area): …`，并以 `Co-Authored-By: Claude Code <noreply@anthropic.com>` 结束。

## Review Focus

| 输入 / 失败模式 | 合理预期 | 钉住它的测试 |
| --- | --- | --- |
| 报价日期是昨天（节假日前收盘价） | 不成交、不写订单，tick 明确记录原因 | `TradingExecutorTest.quote_date_mismatch_executes_nothing` |
| 当天买入的股票触发止损 | T+1 可用为 0，记 `REJECTED`，不卖 | `TradingExecutorTest.same_day_buy_is_not_sellable` |
| 同一 tick 被并发/重复执行 | 同一计划项只成交一次 | `TradingExecutorTest.repeated_tick_fills_once` |
| 盘前模型输出半成品计划后失败 | 数据库不出现该计划，09:45 可以重跑 | `TradingServiceTest.failed_phase_does_not_commit_staged_plan` |
| 报价为空/停牌（价格 0） | 跳过该代码，不把 0 当成交价 | `TradingExecutorTest.zero_price_quote_is_skipped` |

---

### Task 1: Agent 完整追踪（thinking / prompts / calls）

**Files:**
- Modify: `core/src/main/java/com/investmentresearch/core/agent/AgentProperties.java`
- Modify: `core/src/main/java/com/investmentresearch/core/agent/StreamListener.java`
- Modify: `core/src/main/java/com/investmentresearch/core/agent/AgentEngine.java`
- Modify: `core/src/main/java/com/investmentresearch/core/run/RunRecorder.java`
- Test: `core/src/test/java/com/investmentresearch/core/run/RunRecorderTest.java`
- Test: `core/src/test/java/com/investmentresearch/core/agent/AgentEngineTest.java`
- Test: `core/src/test/java/com/investmentresearch/core/agent/ScriptedModel.java`

**Interfaces:**
- Consumes: 现有 `RunRecorder(Clock, String, int, Map)`, `AgentEngine(ToolRegistry, AgentProperties, Supplier<Model>)`.
- Produces:
  - `AgentProperties(String model, Path skillsDir, Path dataDir, int maxTurns, String promptResource, boolean fullTrace)`；保留 4 参构造器，默认 `"/prompts/system.md"`、`false`。
  - `RunRecorder.recordPrompt(String text)`、`recordCall(int round, List<Map<String,Object>> added)`、`recordThinking(String text)`。
  - `StreamListener.default void thinking(String delta) {}`
  - `AgentEngine` 在 `fullTrace=true` 时挂 `TraceMiddleware`，并把思考增量、最终 prompts/calls/thinking 写进 run payload。

- [ ] **Step 1: 写失败测试（RunRecorder 三个新方法）**

在 `RunRecorderTest` 增加：

```java
@Test
void records_prompts_calls_and_thinking() {
    RunRecorder recorder = new RunRecorder(Clock.fixed(Instant.parse("2026-11-07T01:25:00Z"), ZoneId.of("Asia/Shanghai")), "m", 14, Map.of());
    recorder.start(-20261107L, "盘前");
    recorder.recordPrompt("系统提示");
    recorder.recordPrompt("系统提示"); // 同一个 prompt 只记一次
    recorder.recordCall(1, List.of(Map.of("role", "USER", "text", "问题")));
    recorder.recordThinking("先看账户");
    recorder.finish("answered", null);

    Map<String, Object> payload = recorder.payload();
    assertThat((List<?>) payload.get("prompts")).hasSize(1);
    assertThat((List<?>) payload.get("calls")).hasSize(1);
    assertThat(payload.get("thinking")).isEqualTo("先看账户");
}
```

在 `ScriptedModel` 增加：

```java
public static ChatResponse thinkingThenText(String thinking, String text) {
    return ChatResponse.builder()
            .id("reply")
            .content(List.of(
                    ThinkingBlock.builder().thinking(thinking).build(),
                    TextBlock.builder().text(text).build()))
            .finishReason("stop")
            .build();
}
```

在 `AgentEngineTest` 增加（`engine(...)` 辅助方法增加一个重载，接受 `fullTrace`）：

```java
@Test
void full_trace_records_prompt_calls_and_thinking() {
    ScriptedModel model = new ScriptedModel(
            ScriptedModel.thinkingThenText("先想想", "回答[[profile:605499]]。"));
    RecordingListener listener = new RecordingListener();

    AskResult result = engine(10, true, model, new FakeProfileTool())
            .ask(1, "605499", "东鹏饮料", "靠什么赚钱", listener);

    Map<String, Object> payload = result.run().payload();
    assertThat((List<?>) payload.get("prompts")).isNotEmpty();
    assertThat((List<?>) payload.get("calls")).isNotEmpty();
    assertThat(payload.get("thinking")).isEqualTo("先想想");
    assertThat(listener.thinking.toString()).isEqualTo("先想想");
}
```

`RecordingListener` 增加 `StringBuilder thinking` 和 `@Override public void thinking(String delta) { thinking.append(delta); }`。

- [ ] **Step 2: 运行测试确认失败**

```bash
./mvnw -q -pl core -am test -Dtest=RunRecorderTest,AgentEngineTest
```

Expected: 编译失败（`recordPrompt` / `thinking` 不存在）。

- [ ] **Step 3: 实现**

`AgentProperties`：

```java
public record AgentProperties(
        String model, Path skillsDir, Path dataDir, int maxTurns,
        String promptResource, boolean fullTrace) {
    public AgentProperties(String model, Path skillsDir, Path dataDir, int maxTurns) {
        this(model, skillsDir, dataDir, maxTurns, "/prompts/system.md", false);
    }
    // compact constructor 里 promptResource 为空时回落 "/prompts/system.md"
}
```

`RunRecorder`：`payload` 里新增 `thinking`（初始 `""`）。`recordPrompt` 用 SHA-256 前 12 位去重，写入 `{hash, chars, text}`；`recordCall` 写 `{round, added}`；`recordThinking` 追加拼接。

`StreamListener` 增加默认方法。

`AgentEngine`：

- 删除静态 `SYSTEM_PROMPT`，改为构造器里 `loadPrompt(properties.promptResource())`，`buildAgent` 用实例字段。
- 私有 `TraceMiddleware implements MiddlewareBase`：`order()` 返回 `50`（在 `CompanyPromptMiddleware` 的 100 之后拿到最终 system prompt）；`onSystemPrompt` 调 `recorder.recordPrompt(prompt)`；`onReasoning` 取 `ctx.get(RunState.class)`，把 `input.messages()` 自 `RunState.lastMessageCount` 起的新消息转成 `List<Map<String,Object>>` 后 `recordCall(state.round.get() + 1, added)`，并更新 `lastMessageCount`。
- 消息转换：`Msg.getRole()` → `role`；内容块按 `TextBlock`→`{"type":"text","text"}`、`ThinkingBlock`→`{"type":"thinking","text"}`、`ToolUseBlock`→`{"type":"tool_use","id","name","input"}`、`ToolResultBlock`→`{"type":"tool_result","tool_call_id","name","text"}` 输出，其余输出 `{"type": 类名}`。
- 只有 `properties.fullTrace()` 时才 `middleware(new TraceMiddleware(...))`；`CompanyPromptMiddleware` 保持常驻。
- `handleEvent` 增加 `case THINKING_BLOCK_DELTA ->`：累加 `state.thinking`，`listener.thinking(delta)`。
- 正常结束时 `recorder.recordThinking(state.thinking.toString())`。

- [ ] **Step 4: 运行测试确认通过**

```bash
./mvnw -q -pl core -am test -Dtest=RunRecorderTest,AgentEngineTest
```

Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add core/src/main/java/com/investmentresearch/core/agent core/src/main/java/com/investmentresearch/core/run/RunRecorder.java core/src/test/java/com/investmentresearch/core/agent core/src/test/java/com/investmentresearch/core/run/RunRecorderTest.java
git commit -m "feat(agent): 记录完整追踪（prompts/calls/thinking）" -m "Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 2: 失败阶段保留部分证据

**Files:**
- Modify: `core/src/main/java/com/investmentresearch/core/agent/EngineFailure.java`
- Modify: `core/src/main/java/com/investmentresearch/core/agent/AgentEngine.java`
- Test: `core/src/test/java/com/investmentresearch/core/agent/AgentEngineTest.java`

**Interfaces:**
- Consumes: Task 1 的 `RunState.raw/sources/trace`。
- Produces: `EngineFailure` 新增 `raw()`, `sources()`, `trace()`；旧 3 参构造器保留，新 6 参构造器给 `AgentEngine` 用。

- [ ] **Step 1: 写失败测试**

在 `AgentEngineTest.engine_failure_still_carries_a_finished_error_run` 末尾补：

```java
assertThat(failure.raw()).isNotEmpty();
assertThat(failure.trace()).isNotEmpty();
assertThat(failure.sources()).extracting(Source::id).containsExactly("profile:605499");
```

- [ ] **Step 2: 运行确认失败**

```bash
./mvnw -q -pl core -am test -Dtest=AgentEngineTest#engine_failure_still_carries_a_finished_error_run
```

Expected: 编译失败（`raw()` 不存在）。

- [ ] **Step 3: 实现**

`EngineFailure` 增加字段与访问器；3 参构造器委托到 6 参（空列表）。`AgentEngine` 的 catch 分支用 `state.raw()`、`state.sources.values()`、`state.trace` 构造失败对象。

- [ ] **Step 4: 运行确认通过**

```bash
./mvnw -q -pl core -am test -Dtest=AgentEngineTest
```

- [ ] **Step 5: 提交**

```bash
git add core/src/main/java/com/investmentresearch/core/agent core/src/test/java/com/investmentresearch/core/agent/AgentEngineTest.java
git commit -m "feat(agent): 失败运行保留部分工具证据" -m "Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 3: 交易领域模型与规则

**Files:**
- Create: `core/src/main/java/com/investmentresearch/core/domain/trade/TradingPhase.java`
- Create: `core/src/main/java/com/investmentresearch/core/domain/trade/Side.java`
- Create: `core/src/main/java/com/investmentresearch/core/domain/trade/ItemStatus.java`
- Create: `core/src/main/java/com/investmentresearch/core/domain/trade/ItemSource.java`
- Create: `core/src/main/java/com/investmentresearch/core/domain/trade/Quote.java`
- Create: `core/src/main/java/com/investmentresearch/core/domain/trade/TradeAccount.java`
- Create: `core/src/main/java/com/investmentresearch/core/domain/trade/Position.java`
- Create: `core/src/main/java/com/investmentresearch/core/domain/trade/PlanItem.java`
- Create: `core/src/main/java/com/investmentresearch/core/domain/trade/TradePlan.java`
- Create: `core/src/main/java/com/investmentresearch/core/domain/trade/TradeOrder.java`
- Create: `core/src/main/java/com/investmentresearch/core/domain/trade/JournalEntry.java`
- Create: `core/src/main/java/com/investmentresearch/core/domain/trade/EquitySnapshot.java`
- Create: `core/src/main/java/com/investmentresearch/core/trading/TradingRules.java`
- Test: `core/src/test/java/com/investmentresearch/core/trading/TradingRulesTest.java`

**Interfaces:**
- Produces（后续任务直接使用这些名字）：
  - 枚举：`TradingPhase { PRE_MARKET("pre_market"), MIDDAY("midday"), CLOSE("close") }` 带 `dbValue()` 与 `fromDb(String)`；`Side { BUY, SELL }`；`ItemStatus { PENDING, FILLED, CANCELLED, REJECTED, EXPIRED }`；`ItemSource { AGENT, AUTO }`。
  - `Quote(String code, String name, long priceCents, long prevCloseCents, long openCents, long highCents, long lowCents, String quoteDate, String quoteTime, long fetchedAtMillis)`，`boolean tradable()` = `priceCents > 0`。
  - `TradeAccount(long initialCents, long cashCents)`。
  - `Position(String code, String name, int shares, long avgCostCents)`。
  - `PlanItem(long id, long planId, String code, String name, Side side, Long triggerPriceCents, int shares, Long stopLossCents, Long takeProfitCents, String reason, ItemStatus status, ItemSource source, int filledShares, Long filledPriceCents, Long orderId, String note)`。
  - `TradePlan(long id, String tradeDate, TradingPhase phase, int version, String notes, String runId, String status, List<PlanItem> items)`。
  - `TradeOrder(long id, String tradeDate, Long planItemId, String code, String name, Side side, long priceCents, int shares, long amountCents, long feeCents, long taxCents, Long realizedPnlCents, String reason, String quoteTime)`。
  - `JournalEntry(long id, String tradeDate, TradingPhase phase, String text, String runId)`。
  - `EquitySnapshot(String tradeDate, long cashCents, long marketValueCents, long equityCents, long dayPnlCents, Map<String, Long> prices)`。
  - `TradingRules` 的静态常量与方法（见 Step 3）。

- [ ] **Step 1: 写失败测试**

```java
class TradingRulesTest {
    @Test
    void commission_has_a_five_yuan_floor() {
        assertThat(TradingRules.commission(100_000)).isEqualTo(500);      // 1000 元 -> 0.25 元，取 5 元
        assertThat(TradingRules.commission(1_000_000)).isEqualTo(2_500);  // 1 万元 -> 2.5 元
    }

    @Test
    void stamp_tax_is_one_per_mille_on_sell_amount() {
        assertThat(TradingRules.stampTax(1_000_000)).isEqualTo(1_000);
    }

    @Test
    void average_cost_includes_buy_commission() {
        // 100 股 @10.00 + 100 股 @11.00，佣金 5 元
        assertThat(TradingRules.averageCost(1000, 100, 1100, 100, 500)).isEqualTo(1053);
    }

    @Test
    void realized_pnl_subtracts_both_fees() {
        // 卖 100 股 @11.00：110000 - 500 佣金 - 110 印花税 - 100*1053
        assertThat(TradingRules.realizedPnl(1100, 100, 1053, 500, 110)).isEqualTo(4_090);
    }

    @Test
    void sellable_shares_exclude_same_day_buys() {
        assertThat(TradingRules.sellableShares(300, 100)).isEqualTo(200);
        assertThat(TradingRules.sellableShares(100, 100)).isZero();
        assertThat(TradingRules.sellableShares(100, 0)).isEqualTo(100);
    }

    @Test
    void triggers_are_inclusive() {
        assertThat(TradingRules.buyTriggered(1050, 1050)).isTrue();
        assertThat(TradingRules.buyTriggered(1050, 1051)).isFalse();
        assertThat(TradingRules.sellTriggered(1000, 1300, 1000)).isTrue(); // 止损
        assertThat(TradingRules.sellTriggered(1000, 1300, 1300)).isTrue(); // 止盈
        assertThat(TradingRules.sellTriggered(1000, 1300, 1100)).isFalse();
    }

    @Test
    void day_pnl_on_first_day_compares_with_initial_capital() {
        assertThat(TradingRules.dayPnl(103_000_000L, 100_000_000L)).isEqualTo(3_000_000L);
    }
}
```

- [ ] **Step 2: 运行确认失败**

```bash
./mvnw -q -pl core -am test -Dtest=TradingRulesTest
```

Expected: 编译失败（类不存在）。

- [ ] **Step 3: 实现**

在 `TradingRules` 中固定：

```java
public static final long INITIAL_CAPITAL_CENTS = 100_000_000L; // 100 万元
public static final int LOT = 100;
public static final int MAX_POSITIONS = 5;
public static final double MAX_SINGLE_POSITION = 0.20;
public static final double COMMISSION_RATE = 0.00025;
public static final long MIN_COMMISSION_CENTS = 500;
public static final double STAMP_TAX_RATE = 0.001;

public static long commission(long amountCents)      // max(500, round(amount*0.00025))
public static long stampTax(long amountCents)        // round(amount*0.001)
public static long buyCost(long priceCents, int shares)  // price*shares + commission
public static long sellProceeds(long priceCents, int shares) // amount - commission - stampTax
public static boolean isLot(int shares)              // shares > 0 && shares % 100 == 0
public static int sellableShares(int shares, int boughtToday) // max(0, shares - boughtToday)
public static boolean buyTriggered(long limitCents, long priceCents)   // price <= limit
public static boolean sellTriggered(Long stopCents, Long takeCents, long priceCents)
public static long averageCost(long oldAvg, int oldShares, long priceCents, int newShares, long feeCents)
public static long realizedPnl(long sellPrice, int shares, long avgCost, long fee, long tax)
public static long equity(long cashCents, List<Position> positions, Map<String, Long> prices)
public static long dayPnl(long equityCents, long previousEquityCents)
```

`averageCost` 用 `Math.round((oldAvg*oldShares + price*newShares + fee) / (double)(oldShares+newShares))`；`realizedPnl = price*shares - fee - tax - avgCost*shares`；`dayPnl = equity - previous`。

- [ ] **Step 4: 运行确认通过**

```bash
./mvnw -q -pl core -am test -Dtest=TradingRulesTest
```

- [ ] **Step 5: 提交**

```bash
git add core/src/main/java/com/investmentresearch/core/domain/trade core/src/main/java/com/investmentresearch/core/trading/TradingRules.java core/src/test/java/com/investmentresearch/core/trading/TradingRulesTest.java
git commit -m "feat(trading): 交易领域模型与费用/触发规则" -m "Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 4: V13 迁移与 TradingRepository

**Files:**
- Create: `data/src/main/resources/db/migration/sqlite/V13__trading.sql`
- Create: `core/src/main/java/com/investmentresearch/core/port/TradingRepository.java`
- Create: `data/src/main/java/com/investmentresearch/data/persistence/TradingRepositoryImpl.java`
- Test: `data/src/test/java/com/investmentresearch/data/persistence/TradingRepositoryImplTest.java`

**Interfaces:**
- Consumes: Task 3 的全部领域记录。
- Produces:

```java
public interface TradingRepository {
    TradeAccount account();
    void saveAccount(TradeAccount account);

    List<Position> positions();
    void savePosition(Position position);
    void deletePosition(String code);

    TradePlan activePlan(String tradeDate);          // 无则 null，items 已填充
    TradePlan plan(long planId);
    List<TradePlan> plans(String tradeDate);         // 不含 items，按 version 升序
    long insertPlan(TradePlan plan);                 // 返回 id
    void updatePlanStatus(long planId, String status);
    void cancelPendingAgentItems(long planId);
    List<PlanItem> pendingAutoSellItems(long planId);

    PlanItem planItem(long planItemId);
    long insertPlanItem(PlanItem item);
    void updatePlanItem(PlanItem item);
    boolean claimPendingItem(long planItemId, int filledShares, long priceCents); // UPDATE ... WHERE status='PENDING'

    long insertOrder(TradeOrder order);              // 返回 id
    List<TradeOrder> orders(String tradeDate);
    long realizedPnl(String tradeDate);              // 当天 SELL 订单 sum

    void saveJournal(JournalEntry journal);
    List<JournalEntry> journals(String tradeDate);
    List<JournalEntry> recentJournals(int limit);

    void savePhase(PhaseRecord phase);               // upsert by (trade_date, phase)
    PhaseRecord phase(String tradeDate, TradingPhase phase);
    PhaseRecord phaseById(long id);
    List<PhaseRecord> phases(String tradeDate);
    int markRunningPhasesError(String error);        // 启动时把残留 RUNNING 置 ERROR，返回行数
    int expirePendingItems(String tradeDate);        // 15:00 后把 PENDING 项置 EXPIRED

    TickRecord latestTick(String tradeDate);
    void saveTick(TickRecord tick);

    EquitySnapshot equity(String tradeDate);
    List<EquitySnapshot> equitySeries();
    void saveEquity(EquitySnapshot snapshot);

    List<String> tradeDates(int limit);              // trading_phase/trading_equity/orders 有记录的日期，倒序

    <T> T transaction(Supplier<T> work);
}
```

`PhaseRecord`、`TickRecord` 也放在 `core.domain.trade`：

```java
public record PhaseRecord(long id, String tradeDate, TradingPhase phase, String status,
                          String startedAt, String finishedAt, String runId,
                          String payloadJson, String error) {}
public record TickRecord(long id, String tradeDate, String checkedAt, String quotesJson,
                         String executedJson, String error) {}
```

- [ ] **Step 1: 写迁移**

`V13__trading.sql` 按 spec §8 建 9 张表（`trade_account` 初始化一行 `100000000`；`trading_phase` 唯一键 `(trade_date, phase)`；索引照 spec）。注释说明金额单位是分。

- [ ] **Step 2: 写失败测试**

`TradingRepositoryImplTest` 用 `@TempDir` + Flyway + `DriverManagerDataSource` + `JdbcTemplate` + `DataSourceTransactionManager` 建库，覆盖：

```java
@Test void account_and_positions_round_trip();
@Test void active_plan_returns_only_active_with_items();
@Test void claim_pending_item_is_single_shot();   // 第二次返回 false
@Test void transaction_rolls_back_on_exception(); // 插订单后抛错，orders 仍为空
@Test void phase_upsert_replaces_payload();       // 同 date+phase 写两次，payload 是第二次
@Test void realized_pnl_sums_sell_orders_of_the_day();
@Test void running_phases_become_error_and_pending_items_expire();
```

- [ ] **Step 3: 运行确认失败**

```bash
./mvnw -q -pl data -am test -Dtest=TradingRepositoryImplTest
```

Expected: 编译失败。

- [ ] **Step 4: 实现**

`TradingRepositoryImpl` 用 `JdbcTemplate`，构造器 `(JdbcTemplate jdbc, PlatformTransactionManager txManager, ObjectMapper json)`，`@Autowired` 三参构造器；`transaction` 用 `new TransactionTemplate(txManager).execute(...)`。时间戳统一 `LocalDateTime.now()` 按 `uuuu-MM-dd'T'HH:mm:ss.SSSSSS` 格式化；金额列用 `getLong`。

- [ ] **Step 5: 运行确认通过**

```bash
./mvnw -q -pl data -am test -Dtest=TradingRepositoryImplTest
```

- [ ] **Step 6: 提交**

```bash
git add data/src/main/resources/db/migration/sqlite/V13__trading.sql core/src/main/java/com/investmentresearch/core/port/TradingRepository.java data/src/main/java/com/investmentresearch/data/persistence/TradingRepositoryImpl.java data/src/test/java/com/investmentresearch/data/persistence/TradingRepositoryImplTest.java
git commit -m "feat(trading): V13 表结构与交易仓储" -m "Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 5: 批量实时报价

**Files:**
- Create: `core/src/main/java/com/investmentresearch/core/port/QuotePort.java`
- Create: `data/src/main/java/com/investmentresearch/data/adapter/market/SinaStockQuoteAdapter.java`
- Test: `data/src/test/java/com/investmentresearch/data/adapter/market/SinaStockQuoteAdapterTest.java`

**Interfaces:**
- Produces:

```java
public interface QuotePort {
    /** 一次请求批量取，最多 20 只；停牌/无数据的代码不会出现在结果里。 */
    List<Quote> quotes(List<String> codes);
}
```

- [ ] **Step 1: 写失败测试**

用 `UpstreamClient` 测试替身返回固定 GB18030 文本：

```
var hq_str_sh600000="浦发银行,9.90,9.80,10.10,10.20,9.70,10.09,10.10,1234567,123456789.000,2026-11-07,15:00:00,00";
var hq_str_sz000001="";
```

断言：

```java
assertThat(quotes).hasSize(1);
assertThat(quotes.get(0).code()).isEqualTo("600000");
assertThat(quotes.get(0).priceCents()).isEqualTo(1010);
assertThat(quotes.get(0).prevCloseCents()).isEqualTo(980);
assertThat(quotes.get(0).quoteDate()).isEqualTo("2026-11-07");
assertThat(quotes.get(0).quoteTime()).isEqualTo("15:00:00");
assertThat(quotes.get(0).tradable()).isTrue();
```

再断言：`quotes(List.of())` 不发请求返回空；超过 20 只抛 `IllegalArgumentException`；`4`/`8` 开头的代码被跳过。

- [ ] **Step 2: 运行确认失败**

```bash
./mvnw -q -pl data -am test -Dtest=SinaStockQuoteAdapterTest
```

- [ ] **Step 3: 实现**

`SinaStockQuoteAdapter implements QuotePort`，`@Component`，构造器注入 `UpstreamClient`。URL `https://hq.sinajs.cn/list=` + 代码前缀拼接；`GB18030` + `Referer: https://finance.sina.com.cn/`；复用 `SinaIndexSource` 的正则 `hq_str_([a-z]{2}\d{6})="([^"]*)"`。字段：0 名称、1 今开、2 昨收、3 现价、4 最高、5 最低、8 成交量、9 成交额、30 日期、31 时间（`split(",", -1)` 后按下标，注意字段总数 ≥ 32）。价格 `Math.round(price * 100)`；价格或昨收为 0/空 → 跳过。

- [ ] **Step 4: 运行确认通过**

```bash
./mvnw -q -pl data -am test -Dtest=SinaStockQuoteAdapterTest
```

- [ ] **Step 5: 提交**

```bash
git add core/src/main/java/com/investmentresearch/core/port/QuotePort.java data/src/main/java/com/investmentresearch/data/adapter/market/SinaStockQuoteAdapter.java data/src/test/java/com/investmentresearch/data/adapter/market/SinaStockQuoteAdapterTest.java
git commit -m "feat(trading): 新浪批量实时报价适配器" -m "Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 6: 交易时段与交易日门禁

**Files:**
- Create: `core/src/main/java/com/investmentresearch/core/trading/MarketSession.java`
- Create: `core/src/main/java/com/investmentresearch/core/trading/TradingDayGate.java`
- Test: `core/src/test/java/com/investmentresearch/core/trading/MarketSessionTest.java`
- Test: `core/src/test/java/com/investmentresearch/core/trading/TradingDayGateTest.java`

**Interfaces:**
- Produces:

```java
public final class MarketSession {
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    public static boolean inContinuousTrading(LocalTime time); // [09:30,11:30] ∪ [13:00,15:00)
    public static boolean inPreMarketWindow(LocalTime time);   // [09:20,09:45]
    public static boolean inCatchUpWindow(LocalTime time);     // [09:25,14:30]
}

public final class TradingDayGate {
    public enum Status { UNKNOWN, TRADING, HOLIDAY, UNCONFIRMED }
    public TradingDayGate(QuotePort quotes, Clock clock);
    public Status status(LocalDate date);   // 已判定则直接返回缓存
    public Status probe(LocalDate date);    // 请求探测代码，任一报价日期==date -> TRADING，否则 UNCONFIRMED
    public void markHoliday(LocalDate date);
}
```

- [ ] **Step 1: 写失败测试**

`MarketSessionTest`：09:29 不在连续交易、09:30 在、11:30 在、11:31 不在、12:59 不在、13:00 在、14:59 在、15:00 不在。

`TradingDayGateTest`：假 `QuotePort` 返回昨天日期 → `probe` 为 `UNCONFIRMED` 且 `status` 仍是 `UNCONFIRMED`；返回今天 → `TRADING`；第二次 `status` 不再请求上游（计数不变）；`markHoliday` 后 `status` 为 `HOLIDAY`；上游抛异常也返回 `UNCONFIRMED`。

- [ ] **Step 2: 运行确认失败**

```bash
./mvnw -q -pl core -am test -Dtest=MarketSessionTest,TradingDayGateTest
```

- [ ] **Step 3: 实现**

探测代码固定 `List.of("600000", "000001", "600519", "000002", "601318")`；用 `LocalDate.now(clock.withZone(ZONE))` 取「今天」；缓存用 `AtomicReference<LocalDate>` 保存 `confirmed` / `rejected` 两个日期。

- [ ] **Step 4: 运行确认通过**

```bash
./mvnw -q -pl core -am test -Dtest=MarketSessionTest,TradingDayGateTest
```

- [ ] **Step 5: 提交**

```bash
git add core/src/main/java/com/investmentresearch/core/trading/MarketSession.java core/src/main/java/com/investmentresearch/core/trading/TradingDayGate.java core/src/test/java/com/investmentresearch/core/trading/MarketSessionTest.java core/src/test/java/com/investmentresearch/core/trading/TradingDayGateTest.java
git commit -m "feat(trading): 交易时段与交易日门禁" -m "Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 7: 确定性盘中执行器

**Files:**
- Create: `core/src/main/java/com/investmentresearch/core/trading/TradingExecutor.java`
- Create: `core/src/main/java/com/investmentresearch/core/trading/QuoteCache.java`
- Test: `core/src/test/java/com/investmentresearch/core/trading/TradingExecutorTest.java`

**Interfaces:**
- Consumes: Task 3 `TradingRules`/领域记录、Task 4 `TradingRepository`、Task 5 `QuotePort`。
- Produces:

```java
public final class TradingExecutor {
    public TradingExecutor(TradingRepository repo, QuotePort quotes, Clock clock);
    /** 没有 ACTIVE 计划或不在交易时段时返回 null；其他情况总会带回一份 TickResult。 */
    public TickResult tick(String tradeDate);

    public record TickResult(String tradeDate, String checkedAt, List<Quote> quotes,
                             List<Execution> executed, String error, boolean persisted) {}
    public record Execution(String code, Side side, int shares, long priceCents, String note) {}
}

/** 页面读的「最近一次轮询报价」；进程内轻量缓存，不请求上游。 */
public final class QuoteCache {
    public void update(List<Quote> quotes);
    public List<Quote> latest();          // 未轮询过返回空列表
    public Quote byCode(String code);     // 没有返回 null
}
```

`TradingExecutor` 构造器为 `(TradingRepository repo, QuotePort quotes, QuoteCache cache, Clock clock)`；只有报价日期等于 `tradeDate` 时才 `cache.update(quotes)`，日期不匹配不覆盖，页面就不会把昨天的价当今天。

- [ ] **Step 1: 写失败测试**

假 repository（内存实现）+ 假 `QuotePort`，固定 `Clock`。至少覆盖：

```java
@Test void buy_triggers_at_or_below_limit_and_creates_auto_sell_item();
@Test void buy_above_limit_does_nothing();
@Test void sell_stop_and_take_profit_trigger();
@Test void same_day_buy_is_not_sellable();          // 可用 0 -> REJECTED
@Test void repeated_tick_fills_once();              // 同一 item 只一笔订单
@Test void quote_date_mismatch_executes_nothing();  // error 非空、executed 为空
@Test void zero_price_quote_is_skipped();
@Test void tick_persists_at_most_once_per_minute(); // 10 秒内连续 3 次，只有第一次落库
@Test void buy_respects_cash_position_count_and_twenty_percent_cap();
```

断言示例（买入）：

```java
assertThat(result.executed()).hasSize(1);
assertThat(result.executed().get(0).priceCents()).isEqualTo(1049);
assertThat(repo.account().cashCents()).isEqualTo(100_000_000L - 104_900 - 500);
assertThat(repo.positions()).extracting(Position::code).containsExactly("600000");
assertThat(repo.activePlan("2026-11-07").items())
        .anySatisfy(item -> {
            assertThat(item.side()).isEqualTo(Side.SELL);
            assertThat(item.source()).isEqualTo(ItemSource.AUTO);
            assertThat(item.stopLossCents()).isEqualTo(980);
            assertThat(item.takeProfitCents()).isEqualTo(1200);
        });
```

- [ ] **Step 2: 运行确认失败**

```bash
./mvnw -q -pl core -am test -Dtest=TradingExecutorTest
```

- [ ] **Step 3: 实现**

顺序：`activePlan` → 空则返回 null（未到交易时段也返回 null）→ 收集 PENDING 项代码 + 持仓代码去重 → `quotes(codes)` → 任一报价日期不是 `tradeDate` → 返回 error 且不成交 → 逐项执行。

每个成交包在 `repo.transaction(() -> { ... })` 里：先 `claimPendingItem(id, shares, priceCents)`，返回 false 直接结束该笔（并发/重复 tick 只生效一次）；再更新现金/持仓、`insertOrder`、`updatePlanItem`（回填 `filledShares/filledPriceCents/orderId/note`）；BUY 额外插入一条 AUTO SELL 项（复制该 BUY 项写的止损/止盈）。

BUY 校验顺序：`quote.tradable()` → 现金 ≥ `buyCost` → 该票成交后市值 ≤ `20% × equity`（equity = 现金 + 持仓 × 本次报价，缺报价时用持仓成本）→ 持仓数 ≤ 5（已有该票不加数量）。SELL 校验：可用股数 = `TradingRules.sellableShares(position.shares, 今日该票 BUY 成交股数)`；0 → `REJECTED`；不足则按可用成交并写 note。

落库节流：读 `repo.latestTick(tradeDate)`；`executed` 非空或 `error` 非空或距上次 ≥ 60 秒才 `saveTick`。

- [ ] **Step 4: 运行确认通过**

```bash
./mvnw -q -pl core -am test -Dtest=TradingExecutorTest
```

- [ ] **Step 5: 提交**

```bash
git add core/src/main/java/com/investmentresearch/core/trading/TradingExecutor.java core/src/main/java/com/investmentresearch/core/trading/QuoteCache.java core/src/test/java/com/investmentresearch/core/trading/TradingExecutorTest.java
git commit -m "feat(trading): 确定性盘中执行器（T+1/费用/幂等）" -m "Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 8: 交易上下文与 `save_trade_plan`

**Files:**
- Create: `core/src/main/java/com/investmentresearch/core/trading/TradingContext.java`
- Create: `core/src/main/java/com/investmentresearch/core/trading/PlanDraft.java`
- Create: `core/src/main/java/com/investmentresearch/core/tool/SaveTradePlanTool.java`
- Test: `core/src/test/java/com/investmentresearch/core/tool/SaveTradePlanToolTest.java`

**Interfaces:**
- Produces:

```java
public final class TradingContext {
    public synchronized void begin(String tradeDate, TradingPhase phase);
    public synchronized void clear();
    public String tradeDate();                 // null 表示不在阶段中
    public TradingPhase phase();
    public synchronized void stage(PlanDraft plan);
    public synchronized PlanDraft stagedPlan(); // 未暂存为 null
}

public record PlanDraft(String notes, List<PlanDraft.Item> items) {
    public record Item(String code, String name, Side side, Long triggerPriceCents, int shares,
                       Long stopLossCents, Long takeProfitCents, String reason) {}
}

public final class SaveTradePlanTool implements ResearchTool {
    public SaveTradePlanTool(TradingContext context, TradingRepository repo,
                             CompanyDirectory directory, QuotePort quotes, ToolSchemas schemas);
}
```

- [ ] **Step 1: 写失败测试**

`SaveTradePlanToolTest`（内存 repository + 假 directory + 假报价）：

```java
@Test void accepts_a_valid_plan_and_stages_it();
@Test void rejects_missing_or_wrong_date_and_phase();
@Test void rejects_unknown_code_suspended_lot_size_and_bad_side();
@Test void rejects_empty_reason();
@Test void rejects_buy_without_stop_loss_or_take_profit_ordering();
@Test void rejects_buy_price_more_than_21_percent_from_reference();
@Test void rejects_cash_and_position_limit_breaches();
@Test void rejects_duplicate_pending_code_side();
@Test void rejects_midday_buy_for_code_absent_from_premarket_plan();
@Test void requires_a_sell_item_for_every_current_position();
@Test void empty_plan_is_accepted_when_there_are_no_positions();
```

- [ ] **Step 2: 运行确认失败**

```bash
./mvnw -q -pl core -am test -Dtest=SaveTradePlanToolTest
```

- [ ] **Step 3: 实现**

工具参数：`{notes, items:[{code,name?,side,shares,trigger_price?,stop_loss?,take_profit?,reason}]}`。`trade_date`/`phase` 只用来和 `TradingContext` 比对，不作为权威值。

校验按 spec §7.4；参考价取 `QuotePort` 该代码的 `priceCents`，为 0 时用 `prevCloseCents`；两者都 0 → 拒绝「无有效参考价」。成功时 `context.stage(draft)`，`ToolResult.ok(null, null, List.of(), Map.of("accepted", true, "items", n, "notes", notes))`；失败返回 `ToolResult.failure(null, 原因)`。

midday 的「不能新增代码」用 `repo.activePlan(date)` 的 PENDING BUY 代码集合判断。

- [ ] **Step 4: 运行确认通过**

```bash
./mvnw -q -pl core -am test -Dtest=SaveTradePlanToolTest
```

- [ ] **Step 5: 提交**

```bash
git add core/src/main/java/com/investmentresearch/core/trading/TradingContext.java core/src/main/java/com/investmentresearch/core/trading/PlanDraft.java core/src/main/java/com/investmentresearch/core/tool/SaveTradePlanTool.java core/src/test/java/com/investmentresearch/core/tool/SaveTradePlanToolTest.java
git commit -m "feat(trading): 计划暂存与校验工具" -m "Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 9: 状态读取与复盘工具

**Files:**
- Create: `core/src/main/java/com/investmentresearch/core/tool/GetTradingStateTool.java`
- Create: `core/src/main/java/com/investmentresearch/core/tool/SaveTradingJournalTool.java`
- Test: `core/src/test/java/com/investmentresearch/core/tool/GetTradingStateToolTest.java`
- Test: `core/src/test/java/com/investmentresearch/core/tool/SaveTradingJournalToolTest.java`

**Interfaces:**
- Produces:

```java
public final class GetTradingStateTool implements ResearchTool {
    public GetTradingStateTool(TradingRepository repo, QuotePort quotes, TradingContext context,
                               ToolSchemas schemas);
}
public final class SaveTradingJournalTool implements ResearchTool {
    public SaveTradingJournalTool(TradingRepository repo, TradingContext context,
                                  ToolSchemas schemas);
}
```

- [ ] **Step 1: 写失败测试**

`GetTradingStateToolTest`：写入账户/持仓/计划/成交/复盘后，断言 `data` 含 `date/account/positions/plan/orders/journals/rules`；持仓含 `available`（T+1 后）、`price`、`pnl`；报价缺失时价格回落到成本价，不抛错。

`SaveTradingJournalToolTest`：盘前调用被拒绝（`ok=false`，含「盘前不能写复盘」）；午盘/收盘写入成功，`repo.journals(date)` 多一条；空正文被拒绝。

- [ ] **Step 2: 运行确认失败**

```bash
./mvnw -q -pl core -am test -Dtest=GetTradingStateToolTest,SaveTradingJournalToolTest
```

- [ ] **Step 3: 实现**

`GetTradingStateTool` 无必填参数，可选 `date`（默认 `TradingContext.tradeDate()`，没有就用今天）。数据用 `LinkedHashMap` 保持顺序，金额字段同时给「分」和「元」（`cash_yuan` 等）方便模型读。`SaveTradingJournalTool` 参数 `{text}`，正文非空，`phase` 从 `TradingContext` 取。

- [ ] **Step 4: 运行确认通过**

```bash
./mvnw -q -pl core -am test -Dtest=GetTradingStateToolTest,SaveTradingJournalToolTest
```

- [ ] **Step 5: 提交**

```bash
git add core/src/main/java/com/investmentresearch/core/tool/GetTradingStateTool.java core/src/main/java/com/investmentresearch/core/tool/SaveTradingJournalTool.java core/src/test/java/com/investmentresearch/core/tool/GetTradingStateToolTest.java core/src/test/java/com/investmentresearch/core/tool/SaveTradingJournalToolTest.java
git commit -m "feat(trading): 状态读取与复盘工具" -m "Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 10: 阶段编排 `TradingService`

**Files:**
- Create: `core/src/main/java/com/investmentresearch/core/trading/TradingService.java`
- Create: `core/src/main/java/com/investmentresearch/core/trading/PhasePrompt.java`
- Test: `core/src/test/java/com/investmentresearch/core/trading/TradingServiceTest.java`

**Interfaces:**
- Consumes: `AssistantEngine.ask(long, String, String, String, StreamListener)`、Task 8 `TradingContext`/`PlanDraft`、Task 4 repository。
- Produces:

```java
public final class TradingService {
    public TradingService(TradingRepository repo, AssistantEngine engine,
                          TradingContext context, QuoteCache quotes, Clock clock);
    public static long sessionId(String tradeDate);   // 负数：-(yyyyMMdd)
    public static final String MARKET_CODE = "MARKET";
    public void runPhase(String tradeDate, TradingPhase phase, StreamListener listener);
}
```

- [ ] **Step 1: 写失败测试**

脚本化 `AssistantEngine`（记录调用、按阶段设置 `context.stage(...)` 或抛异常）：

```java
@Test void successful_phase_commits_staged_plan_and_persists_payload();
@Test void failed_phase_does_not_commit_staged_plan();
@Test void phase_without_staged_plan_is_error();        // 盘前/午盘
@Test void midday_plan_supersedes_and_inherits_auto_sells();
@Test void pnl_snapshot_written_before_close_journal();
@Test void failure_payload_keeps_partial_raw_and_trace();
```

- [ ] **Step 2: 运行确认失败**

```bash
./mvnw -q -pl core -am test -Dtest=TradingServiceTest
```

- [ ] **Step 3: 实现**

流程：

1. `context.begin(tradeDate, phase)`；盘前先 `engine.deleteSession(sessionId(tradeDate))`。
2. 组装用户提示词（`PhasePrompt.userMessage(repo, phase, date)`：账户、持仓、计划、最近 5 篇复盘、当日约束）。
3. `engine.ask(sessionId(date), "MARKET", "大盘", prompt, listener)`。
4. 成功后，若有 `context.stagedPlan()`：在一个 `repo.transaction` 里把旧 ACTIVE 置 `SUPERSEDED`、写新计划（version = 当天最大 + 1）、作废旧 PENDING AGENT 项、复制旧 PENDING AUTO 项（新计划同代码已有 SELL 则不复制，旧项 `CANCELLED`）。
5. 用 Jackson 组 payload `{answer, sources, trace, issues, raw, run, thinking}`（`thinking` 从 `run.payload().get("thinking")` 取），`repo.savePhase(...)` 状态 `OK`。
6. 盘前/午盘缺少 staged plan → `ERROR`；收盘前先写 `trading_equity`（`QuoteCache` 最新报价，没有则用持仓成本价），再要求 journal 存在，否则 `ERROR`。
7. `catch (EngineFailure)` → payload 用 `failure.raw()/sources()/trace()/run()`，状态 `ERROR`，错误文本原样。
8. `finally` 里 `context.clear()`。

- [ ] **Step 4: 运行确认通过**

```bash
./mvnw -q -pl core -am test -Dtest=TradingServiceTest
```

- [ ] **Step 5: 提交**

```bash
git add core/src/main/java/com/investmentresearch/core/trading/TradingService.java core/src/main/java/com/investmentresearch/core/trading/PhasePrompt.java core/src/test/java/com/investmentresearch/core/trading/TradingServiceTest.java
git commit -m "feat(trading): 阶段编排、计划提交与权益快照" -m "Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 11: 交易提示词、工具 schema 与装配

**Files:**
- Create: `core/src/main/resources/prompts/trading.md`
- Modify: `core/src/main/resources/tool-schemas.json`（手工追加 3 条）
- Modify: `core/src/main/java/com/investmentresearch/core/tool/ToolRegistry.java`（`plus`）
- Modify: `core/src/main/java/com/investmentresearch/core/tool/ToolSchemasTest.java`
- Create: `app/src/main/java/com/investmentresearch/app/config/TradingConfig.java`
- Modify: `app/src/main/resources/application.yml`
- Test: `core/src/test/java/com/investmentresearch/core/tool/ToolRegistryTest.java`
- Test: `app/src/test/java/com/investmentresearch/app/TradingWiringTest.java`

**Interfaces:**
- Produces:
  - `ToolRegistry.plus(List<ResearchTool> extra)`：保持原顺序拼接，共享 `ToolMetrics`。
  - `TradingConfig` 本任务只装配：`tradingToolRegistry`（`@Qualifier("tradingToolRegistry")`）、`tradingEngine`、`tradingContext`、`tradingService`。`executor/scheduler/view/broadcaster` 的 bean 在后续任务里加到同一个类。
  - 37 条 schema（34 + 3）。

- [ ] **Step 1: 写失败测试**

`ToolRegistryTest`：

```java
@Test void plus_appends_tools_in_order_and_shares_metrics() {
    ToolRegistry base = new ToolRegistry(List.of(toolA));
    ToolRegistry all = base.plus(List.of(toolB));
    assertThat(all.schemas()).extracting(ToolSchema::name).containsExactly("a", "b");
    assertThat(all.metrics()).isSameAs(base.metrics());
}
```

`ToolSchemasTest`：数量 34 → 37，注释补 3 条交易工具。

`TradingWiringTest`（`@SpringBootTest` 同 `AssistantWiringTest`，`@MockitoBean AssistantEngine` 只替换聊天引擎；缺 key 时交易引擎是 `UnavailableEngine`）：断言 `tradingToolRegistry` 的 schema 数 37 且含三个交易工具、`tradingContext`/`tradingService` 存在、聊天 `ToolRegistry` 仍是 34 条。

- [ ] **Step 2: 运行确认失败**

```bash
./mvnw -q -pl core -am test -Dtest=ToolRegistryTest,ToolSchemasTest
```

- [ ] **Step 3: 实现**

- `trading.md` 按 spec §6.1：角色、硬规则（只做模拟盘、只按早盘计划执行、理由必须有「好公司+好价格」、每持仓必须有 SELL 项、不编价格）、三个阶段的指令。
- `tool-schemas.json` 追加 `get_trading_state`、`save_trade_plan`、`save_trading_journal`，参数与工具实现一致；`ToolSchemasTest` 注明是 Java 新增。
- `TradingConfig`：`tradingToolRegistry = toolRegistry.plus(List.of(new GetTradingStateTool(...), new SaveTradePlanTool(...), new SaveTradingJournalTool(...)))`；`tradingEngine` 用 `AgentFactory.create(tradingToolRegistry, new AgentProperties(null, skillsDir, dataDir, 14, "/prompts/trading.md", true), apiKey)`，无 key 用 `AssistantConfig.UnavailableEngine`；`tradingContext`、`tradingService(repo, tradingEngine, context, quoteCache, Clock.systemDefaultZone())`；`QuoteCache` 也在此声明 bean。`application.yml` 加 `spring.task.scheduling.pool.size: 4`、`app.trading.enabled: ${APP_TRADING_ENABLED:true}`、`app.trading.poll-seconds: 10`。

- [ ] **Step 4: 运行确认通过**

```bash
./mvnw -q -pl core -am test -Dtest=ToolRegistryTest,ToolSchemasTest
./mvnw -q -pl app -am test -Dtest=TradingWiringTest
```

- [ ] **Step 5: 提交**

```bash
git add core/src/main/resources/prompts/trading.md core/src/main/resources/tool-schemas.json core/src/main/java/com/investmentresearch/core/tool/ToolRegistry.java core/src/test/java/com/investmentresearch/core/tool/ToolRegistryTest.java core/src/test/java/com/investmentresearch/core/tool/ToolSchemasTest.java app/src/main/java/com/investmentresearch/app/config/TradingConfig.java app/src/main/resources/application.yml app/src/test/java/com/investmentresearch/app/TradingWiringTest.java
git commit -m "feat(trading): 提示词、工具 schema 与装配" -m "Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 12: 状态视图、HTTP 与 SSE

**Files:**
- Create: `core/src/main/java/com/investmentresearch/core/trading/TradingView.java`
- Create: `app/src/main/java/com/investmentresearch/app/api/TradingController.java`
- Create: `app/src/main/java/com/investmentresearch/app/trading/TradingBroadcaster.java`
- Test: `core/src/test/java/com/investmentresearch/core/trading/TradingViewTest.java`
- Test: `app/src/test/java/com/investmentresearch/app/api/TradingControllerTest.java`

**Interfaces:**
- Produces:

```java
public final class TradingView {
    public TradingView(TradingRepository repo, TradingDayGate gate, QuoteCache quotes, Clock clock);
    public Map<String, Object> state(String date);   // date 为空用今天
    public List<String> dates(int limit);
    public List<Map<String, Object>> equitySeries();
    public Map<String, Object> phase(long id);
}

public final class TradingBroadcaster {
    public SseEmitter subscribe();                   // 30 分钟超时
    public void publish(Map<String, Object> event);
}
```

`state` 字段：`date, account{cash,initial,equity,day_pnl}, positions[], plan, orders[], journals[], phases[], tick, today_status, rules`。当前价按 `QuoteCache.latest()` → 最近 tick 报价 → 持仓成本价回落，**不调用 `QuotePort`**。

- [ ] **Step 1: 写失败测试**

`TradingViewTest`：空库 `state` 不抛错、`today_status` 有值；历史日期读 `trading_equity`；今天用 tick 报价算 equity；`phase(id)` 不存在返回 404 语义（`IllegalArgumentException`，Controller 转 404）。

`TradingControllerTest`（`@WebMvcTest` + `@AutoConfigureMockMvc(addFilters = false)`）：GET state/dates/equity/phase 的 JSON 形状；未知 phase id → 404；`/api/trading/stream` 返回 `text/event-stream`，`broadcaster.publish` 后 asyncDispatch 含 `data:`。

- [ ] **Step 2: 运行确认失败**

```bash
./mvnw -q -pl core -am test -Dtest=TradingViewTest
./mvnw -q -pl app -am test -Dtest=TradingControllerTest
```

- [ ] **Step 3: 实现**

Controller 只做参数转换 + 调用 `TradingView`；`@ExceptionHandler(IllegalArgumentException)` → 404。`TradingBroadcaster` 持有 `CopyOnWriteArrayList<SseWriter>`；`@Scheduled(fixedDelay = 20_000)` 发 `heartbeat`；断开时移除。SSE 事件统一 `{type: "phase"|"thinking"|"text"|"tool"|"monitor"|"error"|"heartbeat", ...}`。

`TradingConfig` 追加 beans：`tradingView(repo, gate, quoteCache, clock)`、`tradingBroadcaster()`；`TradingGate` 的 bean 也在本任务声明（`new TradingDayGate(quotePort, clock)`）。Controller 由 `@RestController` 扫描，不额外声明。

- [ ] **Step 4: 运行确认通过**

```bash
./mvnw -q -pl core -am test -Dtest=TradingViewTest
./mvnw -q -pl app -am test -Dtest=TradingControllerTest
```

- [ ] **Step 5: 提交**

```bash
git add core/src/main/java/com/investmentresearch/core/trading/TradingView.java core/src/test/java/com/investmentresearch/core/trading/TradingViewTest.java app/src/main/java/com/investmentresearch/app/api/TradingController.java app/src/main/java/com/investmentresearch/app/trading/TradingBroadcaster.java app/src/test/java/com/investmentresearch/app/api/TradingControllerTest.java
git commit -m "feat(trading): 状态接口与交易 SSE" -m "Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 13: 调度器（交易日门禁、阶段串行、盘中轮询、启动补跑）

**Files:**
- Create: `app/src/main/java/com/investmentresearch/app/trading/TradingScheduler.java`
- Create: `app/src/main/java/com/investmentresearch/app/config/TradingProperties.java`
- Test: `app/src/test/java/com/investmentresearch/app/trading/TradingSchedulerTest.java`

**Interfaces:**
- Produces:

```java
@ConfigurationProperties("app.trading")
public record TradingProperties(Boolean enabled, Integer pollSeconds, Integer phaseTimeoutMinutes) {
    public TradingProperties {
        enabled = enabled == null ? Boolean.TRUE : enabled;   // 缺省开着；显式 false 才关
        pollSeconds = pollSeconds == null || pollSeconds <= 0 ? 10 : pollSeconds;
        phaseTimeoutMinutes = phaseTimeoutMinutes == null || phaseTimeoutMinutes <= 0
                ? 15 : phaseTimeoutMinutes;
    }
}

public final class TradingScheduler {
    TradingScheduler(TradingService service, TradingExecutor executor,
                     TradingDayGate gate, TradingRepository repo,
                     TradingBroadcaster broadcaster, TradingProperties props, Clock clock);
    @Scheduled(cron = "0 25,30,35,40,45 9 * * MON-FRI", zone = "Asia/Shanghai")
    void preMarketWindow();
    @Scheduled(cron = "0 35 11 * * MON-FRI", zone = "Asia/Shanghai")
    void midday();
    @Scheduled(cron = "0 5 15 * * MON-FRI", zone = "Asia/Shanghai")
    void close();
    @Scheduled(fixedDelayString = "#{${app.trading.poll-seconds:10} * 1000}")
    void tick();
    @EventListener(ApplicationReadyEvent.class)
    void catchUp();
}
```

- [ ] **Step 1: 写失败测试**

用固定 `Clock` + 假 service/executor/gate，直接调用包级方法：

```java
@Test void holiday_runs_nothing();                          // gate=HOLIDAY -> service 未调用
@Test void premarket_waits_until_gate_confirms();           // 09:25 UNCONFIRMED -> 不跑；09:30 TRADING -> 跑一次
@Test void unconfirmed_after_0945_marks_holiday();          // 09:45 仍 UNCONFIRMED -> markHoliday
@Test void failed_premarket_retries_once_at_0945();         // 09:25 ERROR -> 09:45 再跑一次，之后不再跑
@Test void midday_and_close_skip_on_non_trading_day();
@Test void tick_only_in_continuous_session_and_after_gate(); 
@Test void startup_catch_up_runs_premarket_only_inside_window_and_without_plan();
@Test void phases_do_not_overlap();                         // 一个 RUNNING 时第二个直接返回
@Test void close_expires_pending_items_before_review();
@Test void startup_marks_stale_running_phases_error();
```

- [ ] **Step 2: 运行确认失败**

```bash
./mvnw -q -pl app -am test -Dtest=TradingSchedulerTest
```

- [ ] **Step 3: 实现**

- 所有入口先看 `props.enabled()`、`gate.status(today)`；`UNKNOWN` 时 `probe`。
- 09:25–09:45 窗口：`TRADING` 且当天没有 `trading_phase` 记录 → 跑盘前；已有 `OK` 直接返回；`ERROR` 只在 09:45 重试一次（用 `AtomicBoolean retried`）。
- 09:45 仍 `UNCONFIRMED` → `gate.markHoliday(today)`，当天不再运行。
- 阶段串行：`AtomicBoolean phaseRunning`；阶段提交到单线程 `ExecutorService`，`future.get(phaseTimeoutMinutes, MINUTES)`，超时 `cancel(true)` 并写 `ERROR`「阶段超时」。
- 启动补跑：`ApplicationReadyEvent` 时若在 09:25–14:30、`TRADING`、当天无计划 → 跑盘前。
- 盘中 tick：`MarketSession.inContinuousTrading(now)` 且 gate 为 `TRADING` → `executor.tick(date)`；结果非 null 时 `broadcaster.publish({type:"monitor", ...})`。
- 15:05 收盘复盘前先 `repo.expirePendingItems(date)`，复盘完成后再写权益快照（由 `TradingService` 负责快照，调度器负责过期）。
- 进程重启：启动时 `repo.markRunningPhasesError("应用重启中断")`；持仓/计划/成交都在库里，盘中 tick 恢复后继续执行未触发项，不重跑盘前。
- `TradingConfig` 追加 beans：`tradingExecutor(repo, quotePort, quoteCache, clock)`、`tradingScheduler(...)`，并在配置类上 `@EnableConfigurationProperties(TradingProperties.class)`。

- [ ] **Step 4: 运行确认通过**

```bash
./mvnw -q -pl app -am test -Dtest=TradingSchedulerTest
```

并在 `TradingWiringTest` 补断言：`tradingExecutor`、`tradingScheduler`、`tradingView`、`tradingBroadcaster`、`tradingDayGate` 都能从容器取到。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/investmentresearch/app/trading/TradingScheduler.java app/src/main/java/com/investmentresearch/app/config/TradingProperties.java app/src/main/java/com/investmentresearch/app/config/TradingConfig.java app/src/test/java/com/investmentresearch/app/trading/TradingSchedulerTest.java app/src/test/java/com/investmentresearch/app/TradingWiringTest.java
git commit -m "feat(trading): 调度、交易日门禁与启动补跑" -m "Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 14: 「交易」页

**Files:**
- Modify: `frontend/index.html`
- Modify: `frontend/app.js`
- Modify: `frontend/styles.css`

**Interfaces:**
- Consumes: `/api/trading/state|dates|equity|phases/{id}|stream`、现有 `apiFetch`、`renderBody`、`formatDuration`。
- Produces: `tradingOpen` 视图与 `openTrading()/closeTrading()/loadTradingState()/loadTradingPhase(id)/connectTradingStream()`。

- [ ] **Step 1: 加导航与视图骨架**

`index.html`：侧栏在「系统监控」后加 `<button class="archive-entry" @click="openTrading()"><span>交易</span><span class="count">{{ trading.state?.positions?.length || 0 }}</span></button>`；`v-show="!archiveOpen && !metricsOpen && !tradingOpen"`；新增 `<section class="archive-view trading-view" v-if="tradingOpen">`，包含顶部统计、计划卡、阶段卡、持仓表、成交表、复盘卡、tick 折叠列表。

- [ ] **Step 2: 加状态与方法**

`app.js` data 增加：`tradingOpen, tradingLoading, tradingError, tradingDate, tradingDates, trading, tradingPhaseDetail, tradingStream, tradingTimer, tradingPhaseTab`。方法：

- `openTrading()`：打开、拉 dates/state、`setInterval(loadTradingState, 5000)`、`connectTradingStream()`。
- `closeTrading()`：清 interval、abort stream。
- `loadTradingState()`：`apiFetch('/api/trading/state?date=' + tradingDate)`。
- `loadTradingPhase(id)`：`apiFetch('/api/trading/phases/' + id)`。
- `connectTradingStream()`：`fetch('/api/trading/stream', {headers:{Authorization:...}, signal})` + `ReadableStream` 解析 `data: ` 行；`thinking/text` 追加到 `trading.live`；`phase/monitor` 触发一次 `loadTradingState()`。
- 复用 `renderBody(text, sources)` 渲染计划 notes、复盘正文、阶段 answer；引用点击在交易页内展开。

- [ ] **Step 3: 样式**

在 `styles.css` 追加 `.trading-view` 系列（统计行、计划项状态色、持仓/成交表格、tick 列表），沿用 `.archive-*` 的卡片、表格、按钮变量。

- [ ] **Step 4: 版本号与手动验证**

`index.html` 里 `app.js?v=` / `styles.css?v=` 加一；启动应用（`APP_API_TOKEN=test ./mvnw -pl app -am spring-boot:run`，`APP_SKILLS_DIR=../skills`），用浏览器打开交易页，确认：无数据时显示空态、切换日期、计划/持仓/成交表格渲染、引用可展开、SSE 连接不报错。

- [ ] **Step 5: 提交**

```bash
git add frontend/index.html frontend/app.js frontend/styles.css
git commit -m "feat(frontend): 交易页（计划/持仓/成交/复盘/完整过程）" -m "Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 15: 文档与全量验证

**Files:**
- Modify: `README.md`
- Modify: `docs/handover/CONFIGURATION_AND_DEPLOYMENT.md`
- Modify: `docs/handover/ARCHITECTURE.md`
- Modify: `docs/handover/MODULE_DETAILS.md`
- Modify: `docs/handover/DATA_AND_DATABASE.md`
- Modify: `docs/handover/API_AND_INTERFACE.md`
- Modify: `docs/handover/PROJECT_OVERVIEW.md`
- Modify: `docs/handover/TESTING_AND_QUALITY.md`
- Modify: `docs/handover/TODO_AND_KNOWN_ISSUES.md`

- [ ] **Step 1: 更新文档**

记录：V13 与 9 张表；`/api/trading/*`；三个交易工具；`APP_TRADING_ENABLED`、`app.trading.poll-seconds`；交易日门禁与节假日行为；调度窗口；tick 10 秒执行/60 秒落库；已知限制（无滑点、盘前候选可能为空、报价日期刷新时点待确认）。

- [ ] **Step 2: 全量构建**

```bash
./mvnw verify
./mvnw -q spotless:check
```

Expected: BUILD SUCCESS，spotless 退出 0。

- [ ] **Step 3: 真实链路手测（交易日）**

配置 `DEEPSEEK_API_KEY` 与 `APP_API_TOKEN`，在交易日 09:25 前启动，检查：盘前阶段完整过程有 prompts/calls/thinking；计划落库；盘中 tick 有报价；午盘/收盘复盘；页面引用可点开。非交易日启动确认盘前/午盘/收盘都不运行且 `today_status=HOLIDAY`。

- [ ] **Step 4: 提交**

```bash
git add README.md docs/handover
git commit -m "docs: 交易 Agent 模块、接口与限制" -m "Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

## Self-Review

- **Spec coverage:** §5 默认值→Task 3/6/7/13；§6 流程→Task 10/13；§7 规则→Task 3/7/8；§8 数据模型→Task 4；§9 代码结构→Task 3–13；§10 完整记录→Task 1/2/10；§11 HTTP/SSE→Task 12；§12 页面→Task 14；§13 失败边界→Task 7/10/13；§14 测试→各任务；§15 文档→Task 15。
- **类型一致性：** 金额统一 `long` 分；`TradingPhase.dbValue()` 是数据库字符串；`PlanDraft` 只含无 id 的项；`PhaseRecord`/`TickRecord` 在 Task 4 一次定义，后续直接引用。
- **Review Focus 的测试都已落到具体任务**（Task 7 覆盖前四条，Task 10 覆盖半成品计划）。
- **未覆盖但已知：** 前端没有自动化测试，Task 14 用启动手测兜底；真实交易日行为只能人工验证。
