# 评测题库

`questions.json` 是 20 道固定题：5 家冻结公司 × 4 类问题（商业模式 / 经营历史 / 估值 / 财务安全）。
它只描述「问什么、期望模型用什么工具、关键事实是什么」，不含执行逻辑。

## 字段

```json
{
  "schema_version": 1,
  "as_of": "2026-10-30",
  "questions": [
    {
      "id": "q02",
      "code": "605499",
      "name": "东鹏饮料",
      "skill": "operating_history",
      "question": "东鹏饮料过去五年的经营表现怎么样？利润含金量如何？",
      "required_tools_any": ["get_financial_history", "get_finance"],
      "facts": [
        {"tool": "get_finance", "field": "period", "value": "2026-09-30"},
        {"tool": "get_finance", "field": "revenue_yi", "value": 185.96}
      ]
    }
  ]
}
```

- `skill`：期望模型加载的 skill；模型多加载别的 skill 不算错（参考 `LiveSkillSmoke` 的实测行为）。
- `required_tools_any`：至少调用其中一个；模型选哪个由题目决定，不钉死调用顺序。
- `facts`：关键事实的期望值，**来源是 `core/src/test/resources/golden/tools/` 的冻结输出**，不是手抄的当前行情。
  `field` 对应工具结果 `result.data` 下的字段。
- `as_of`：数据快照日期，必须等于所用 golden 的 `generated_on`。

## 校验

`app/src/test/java/com/investmentresearch/app/eval/QuestionBankTest.java` 随 `mvn verify` 运行（不联网、不调模型），检查：

- 20 题、id 唯一、每家公司 4 题、4 类 skill 齐全；
- 公司代码在冻结的 5 家之内，skill 目录与 frontmatter 存在；
- `required_tools_any` 里的工具都在 `tool-schemas.json` 注册；
- 每条 fact 都能在对应 golden 的 `result.data` 里找到且值相等，`generated_on` 与 `as_of` 一致。

改题库或换 golden 后先跑它，避免「题目指向一个已经不存在的数字」。

## 版本戳

`app/src/test/java/com/investmentresearch/app/eval/VersionStamp.java` 生成评测结果头部的版本身份：

| 字段 | 来源 |
| --- | --- |
| `agent_version` | git 短 SHA；工作区脏时追加 `-dirty` |
| `prompt_version` | classpath `/prompts/system.md` 的 SHA-256 前 12 位 |
| `skills_version` / `skills` | `skills/*/SKILL.md` 的逐 skill 哈希与聚合哈希 |
| `tools_version` | classpath `tool-schemas.json` 的哈希 |
| `dataset_version` | `eval/questions.json` 的哈希 |

`as_of` 与 `generated_at` 分开记：前者是数据快照日期，后者是评测时间。

## 执行

`app/src/test/java/com/investmentresearch/app/eval/QuestionBankEval.java` 逐题调用真实模型。类名不带 `Test`，默认不跑：

```bash
LIVE_EVAL=1 ./mvnw -pl app -am test -Dtest=QuestionBankEval -Dsurefire.failIfNoSpecifiedTests=false
# 调试时只跑前 3 题
LIVE_EVAL=1 ./mvnw -pl app -am test -Dtest=QuestionBankEval -Dsurefire.failIfNoSpecifiedTests=false -Deval.limit=3
```

每题自动判五项：正常作答、期望 skill 已加载、`required_tools_any` 至少一个成功调用、没有失败的工具调用、
`facts` 与本次工具结果一致；出现 `unknown_citation` 也算失败。报告写到
`eval/results/<agent>-<prompt>-<model>-<dataset>.json`（已 git 忽略），头部是版本戳、`model_version`、`as_of`，
正文逐题记录 trace、事实核对结果和回答原文。跑之前先跑 `QuestionBankTest`，避免把题库错误算到模型头上。

工具失败分两类：参数/业务校验失败算模型调用错误（判失败）；上游接口报错只记 `tool_warnings`。

## LLM Judge

每题回答再交给 `eval/judge-prompt.md` 打分：grounding / coverage / reasoning / clarity 各 1-5 分，
总分是四项平均，≥4 且每项 ≥3 为 `pass`，否则 `review`（人工复核）。总分与判定由 Java 计算，不信任模型自己的算术。

- 分数只写进报告，不影响 `QuestionBankEval` 的 pass/fail——Judge 有噪声，当前只用来量化与筛低分样本。
- 报告头部多一个 `judge_prompt_version`（judge prompt 的内容哈希）；改了评分标准，这个值会变，历史对比时能区分。
- Judge 复用 `AgentProperties.model()` 和 `DEEPSEEK_API_KEY`，没有额外配置。

## 历史版本对比

每次运行结束后自动在 `eval/results/` 里找**同一 `dataset_version`、配置不同**的上一份报告（agent / prompt / skills / tools / model 任一不同），
结果写进本次报告的 `baseline` 和 `delta`，并在控制台打印一行。同一配置重跑只覆盖自己、不参与对比——那只是随机波动。

- `delta.passed_before` / `passed_after` 只统计两边都有的题（`matched`），partial 运行（`-Deval.limit`）不会被误判成退步。
- `regressions` / `improvements` 是通过状态翻转的题号；`changed` 列出每题前后通过状态与 Judge 总分。
- `judge_average_change` 是两次报告 `judge_average` 的差，任一侧缺失则为 null。

文件名带模型和题库哈希，所以换 prompt、换模型、换题库都会生成新文件，不会互相覆盖。

## CI 门禁

`.github/workflows/eval.yml` 在改动 `skills/`、prompt、`tool-schemas.json`、`core/agent/`、`eval/` 的 PR、push 到 main（或手动触发）上跑全量 20 题：

- 需要仓库 secret `DEEPSEEK_API_KEY`，没有就跳过并留一条 notice（fork PR 不会失败）。
- 门禁参数：`-Deval.min-pass-rate=0.9 -Deval.min-judge-average=3.8`；报告总是先落盘再判定，失败仍能下载 artifact。
- 报告作为 `eval-report` artifact 保存，`$GITHUB_STEP_SUMMARY` 里写版本、通过数、Judge 均分和回归题号。
- 代码层只负责让 job 变红；「阻止合并」要在 GitHub 分支保护里把 `verify` / `eval` 设为 required checks。

## 下一步（尚未实现）

- 引用与事实的关联检查（现在只查「引用不存在」和「事实值」）。
- 低分样本的人工复核清单（`verdict=review` 现在只在报告里标记）。
