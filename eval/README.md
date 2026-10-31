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
`eval/results/<agent_version>-<prompt_version>.json`（已 git 忽略），头部是版本戳、`model_version`、`as_of`，
正文逐题记录 trace、事实核对结果和回答原文。跑之前先跑 `QuestionBankTest`，避免把题库错误算到模型头上。

## 下一步（尚未实现）

- 引用与事实的关联检查（现在只查「引用不存在」和「事实值」）。
- LLM Judge 打分。
- 历史报告对比（同一 `dataset_version` 下比较 `prompt_version` / `model_version` 的分数变化）。
