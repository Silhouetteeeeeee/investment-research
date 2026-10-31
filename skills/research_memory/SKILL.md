---
name: research_memory
description: 按公司维护长期研究档案，回答结论有没有变化、还悬着什么
---

公司研究档案：事实、假设、结论变化与未决问题

档案按公司建立，五个模块各自独立，不是聊天记录的摘要：
- 已验证事实：已经发生、可回溯、有报告期的内容。财务数据、已披露经营事实、公司公开披露的计划（写成"公司披露正在推进全国化"）。必须有 source_id 与 report_period；没有来源的推测不能写成事实。公司披露不等于结果，把"披露"与"已实现"分开写。
- 待验证假设：还没被证实或证伪的判断。必须有 supporting_fact_ids、confidence（低/中/高）、verification_method、falsification_condition（证伪条件）、status（待验证/部分支持/已证实/已证伪）。没有证伪条件的假设不允许写入。
- 当前结论：对一家公司的主要判断。status 只有 有效/已推翻；confidence 低/中/高或留空。新结论用 append_research_record 写入 conclusions，拿到 G 编号。
- 结论变化：只能通过 record_conclusion_change 写入，历史不可改。operation 四种：补充（在旧结论上加信息）、修正（替换表述、保留脉络）、推翻（旧结论作废，新建 G）、维持（复核后结论不变）。补充/修正/推翻都要求 old_conclusion 与档案中的当前 statement 完全一致；推翻必须给 new_title；维持时 old_conclusion 与 new_conclusion 必须相同。每次变化都要写 trigger_event、trigger_date、evidence_ids、reason、impact。
- 未决问题：importance 高/中/低，status 待处理/调查中/已解决/暂时无法解决。写清 missing_evidence、next_action、resolution_criteria；解决时用 update_research_record 改状态并补 resolution_note，原问题保留。

写入规则：
- 一次批量写完，别为补字段反复重试：append_research_record 每条记录缺一个必填字段就会整条被拒，字段名写错还会白耗一轮。工具轮次有限，临近上限时先保留一轮作答；没写完的记录在回答末尾说明未写入。
- 各模块必填字段（一次填全）：
  - facts：fact_type、content（事实正文）、report_period、source_id。
  - hypotheses：statement（假设正文）、supporting_fact_ids（非空）、verification_method、falsification_condition。
  - conclusions：title、statement（结论正文）。
  - questions：question、missing_evidence、next_action、resolution_criteria。
  正文在事实里叫 content，在假设与结论里叫 statement，不要混用。
- 只有形成新的已验证事实，或做了一次实质复核时，才写档案；闲聊、重复结论不写。复核后结论没变，也要用 record_conclusion_change 记一条"维持"，让"上次复核时间"和复核轨迹可追溯。
- 事实与解读分开：可以写"净利润增速高于经营现金流增速"，不能把"盈利质量下降"写进事实；后者进假设或未决问题。
- 更新状态用 update_research_record；不要用 append 重写同一份结论。
- 写入失败时明确告诉用户"档案未写入，原因……"，不要当成已保存。

回答"结论有没有变化"之前，必须先用 read_research_memory 读取 conclusions 和 changes，不能只凭摘要作答；摘要可能被截断，且只带最近一条变化，涉及摘要未展示的记录时先读档案再看。

回答"结论有没有变化"的顺序：
1. 上次复核时间与当前结论基线（read_research_memory conclusions）。
2. 复核以来新增的事实与证据。
3. 逐条对照：哪些结论维持、哪些补充、哪些修正、哪些推翻，以及触发事件。
4. 仍未解决的问题与下一步要补的证据。
5. 本次判断的局限：数据缺口、假设未被验证的部分。

如果公司尚无档案，先做研究；只有形成实质结论或可验证事实后才建档，不要为一次性问答建空档案。
