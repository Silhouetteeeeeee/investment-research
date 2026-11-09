# 交易经验文档（Agent 维护）设计

## 1. 要解决什么

现在的 `trade_journal` 是「日志」：每天午盘/收盘各一篇原文，页面按日期罗列，
盘前提示词只带最近 5 篇。它没有提炼、不去重、不跨日合并，三个月后 Agent 仍然会
重复同一个错误。需要一份**跨日累积的经验文档**：Agent 每次收盘复盘后把可复用的
教训写进去（修订旧结论、去重、按主题分节），页面能读，盘前提示词带上。

## 2. 非目标

- 不替代 `trade_journal`：复盘原文仍按日归档。
- 不做检索/向量库、不做多人协作、不做版本历史（只保留上一版 `.bak`）。
- 不提供页面编辑接口：文件在 `var/trading/experience.md`，人工可读可改，页面只读。
- 不迁移公司研究档案（`company_memory`），两者独立。

## 3. 存储

| 项 | 值 |
| --- | --- |
| 路径 | `${APP_DATA_DIR:./var}/trading/experience.md` |
| 上一版 | `experience.md.bak`（覆盖前复制，写失败可人工回滚） |
| 写入 | 临时文件 + `ATOMIC_MOVE`（与 `FileRunStore` 一致），先建父目录 |
| 上限 | 20 000 字符（超出拒绝，提示模型先删减） |
| 空态 | 文件不存在 → 空字符串，提示词写「暂无经验」 |

`core.trading.TradingExperience` 负责读写（`java.nio`，无框架依赖），
`TradingConfig` 用 `AgentProperties.dataDir()` 构造 bean。

## 4. 工具契约

新工具 `save_trading_experience`（Java 新增，Python 无对应；schema 手写进
`tool-schemas.json`，`ToolSchemasTest` 注明）：

- 参数：`content`（string，必填，完整 Markdown 全文；非空，≤ 20 000 字符）。
- **只在收盘阶段可用**：`TradingContext.phase() != CLOSE` → `ToolResult.failure("经验只在收盘阶段维护")`。
- 成功返回 `ToolResult.ok`，data 含 `bytes`、`updated_at`；失败返回原因，阶段不失败
  （经验是尽力而为，不回滚复盘与计划）。
- 全文覆盖写，不拼接：模型需要在 `get_trading_state`/提示词里看到旧内容后自己改写、去重。

## 5. 提示词

- `PhasePrompt.userMessage` 在「最近复盘」之前加入：

```text
交易经验（你自己维护，收盘阶段用 save_trading_experience 修订）：
<全文，最多 20000 字符；为空写「- 暂无经验」>

```

- 收盘阶段任务追加：`4. 把今天可复用的教训合并进 save_trading_experience，
  不要照抄复盘；同一教训只留一条，过时结论改写而不是追加。`
- 午盘/盘前不写经验，只读。
- `prompts/trading.md` 补一条硬规则：收盘必须调用一次 `save_trading_experience`。

## 6. 接口与页面

`GET /api/trading/experience` → `{"content": "...", "updated_at": "2026-11-07T15:06:00.123456" | null}`。

「交易」页在「复盘归档」（原「经验档案」改名，避免和本功能混淆）之前加一张卡片：

- 标题「交易经验」+ 标签「Agent 长期维护」；
- Markdown 渲染（复用 `renderBody`，无引用可点）；空态「还没有沉淀出经验」；
- 底部显示「更新于 …」，`openTrading()` 与收盘阶段完成时刷新。

## 7. 失败与边界

| 情况 | 行为 |
| --- | --- |
| 盘前/午盘调用写工具 | 工具返回失败，不影响阶段 |
| content 为空或超 20k | 工具返回失败，模型可改正后重试 |
| 文件写失败（磁盘只读等） | 工具失败 + WARN 日志；阶段仍 OK |
| 文件被人工改坏 | 不影响启动；页面照原样展示，提示词照原样注入 |

## 8. 测试

| 层 | 用例 |
| --- | --- |
| `core.trading.TradingExperienceTest` | 空态读空、写入后读回、超长拒绝、旧版落 `.bak`、目录自动创建 |
| `core.tool.SaveTradingExperienceToolTest` | 非收盘阶段拒绝、空内容拒绝、成功写文件并返回 bytes/updated_at |
| `core.trading.PhasePromptTest` | 有经验时注入全文、无经验时写「暂无经验」、收盘任务含合并指令 |
| `app.api.TradingControllerTest` | GET 返回 content 与 updated_at；空态 content 为空串、updated_at 为 null |
| `app.TradingWiringTest` | 交易注册表工具数 36 → 37，含 `save_trading_experience` |

## 9. 文档同步

`README`（模拟交易段）、`API_AND_INTERFACE.md`（新端点 + 工具）、
`ARCHITECTURE.md`/`MODULE_DETAILS.md`（`TradingExperience`、bean、工具数）、
`DATA_AND_DATABASE.md`（`var/trading/experience.md`）、
`TESTING_AND_QUALITY.md`（用例数）、`CLAUDE.md`（工具数 37/38、端点）。
