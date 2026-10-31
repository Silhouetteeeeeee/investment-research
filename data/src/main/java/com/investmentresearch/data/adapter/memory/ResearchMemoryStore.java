package com.investmentresearch.data.adapter.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.investmentresearch.core.port.ResearchMemoryPort;
import com.investmentresearch.core.research.MemoryException;
import com.investmentresearch.core.research.MemoryRules;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** 研究档案：{@code assistant/research_memory.py} 的 SQLite 实现换成 JdbcTemplate。 */
@Component
public class ResearchMemoryStore implements ResearchMemoryPort {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter NOW =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS");
    private static final Map<String, String[]> TABLES = new LinkedHashMap<>();
    private static final Map<String, Set<String>> UPDATE_ALLOWLISTS =
            Map.of(
                    "facts", Set.of("verification_status", "source_locator", "source_url"),
                    "hypotheses", Set.of("status", "confidence", "next_review_date"),
                    "questions", Set.of("status", "last_checked_at", "resolution_note"));

    static {
        // section → 表名、业务 id 列、状态列、排序
        TABLES.put("facts", new String[] {"memory_fact", "fact_id", "verification_status", "created_at DESC, id DESC"});
        TABLES.put("conclusions", new String[] {"memory_conclusion", "conclusion_id", "status", "updated_at DESC, id DESC"});
        TABLES.put("hypotheses", new String[] {"memory_hypothesis", "hypothesis_id", "status", "(next_review_date IS NULL), next_review_date ASC, updated_at DESC, id DESC"});
        TABLES.put("questions", new String[] {"memory_question", "question_id", "status", "CASE importance WHEN '高' THEN 0 WHEN '中' THEN 1 ELSE 2 END, last_checked_at ASC, id ASC"});
        TABLES.put("changes", new String[] {"memory_change", "change_id", "review_status", "created_at DESC, id DESC"});
    }

    private static final Map<String, String> IMPORT_COLUMNS =
            Map.of(
                    "company_memory", "code,name,created_at,updated_at,last_reviewed_at",
                    "memory_fact", "code,fact_id,subject,fact_type,content,metrics_json,report_period,published_at,source_id,source_label,source_locator,source_url,verification_status,dedupe_key,created_at,updated_at",
                    "memory_hypothesis", "code,hypothesis_id,statement,supporting_fact_ids_json,assumptions,confidence,verification_method,falsification_condition,status,next_review_date,created_at,updated_at",
                    "memory_conclusion", "code,conclusion_id,title,statement,status,confidence,based_on_fact_ids_json,based_on_hypothesis_ids_json,last_change_id,created_at,updated_at",
                    "memory_question", "code,question_id,question,importance,known_facts_json,missing_evidence,next_action,resolution_criteria,related_hypothesis_ids_json,status,last_checked_at,resolution_note,created_at,updated_at",
                    "memory_change", "code,change_id,from_conclusion_id,to_conclusion_id,operation,old_conclusion,new_conclusion,trigger_event,trigger_date,evidence_ids_json,reason,impact,review_status,created_at");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;

    @Autowired
    public ResearchMemoryStore(JdbcTemplate jdbc) {
        this(jdbc, Clock.systemDefaultZone());
    }

    public ResearchMemoryStore(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.tx =
                new TransactionTemplate(
                        new DataSourceTransactionManager(jdbc.getDataSource()));
    }

    @Override
    public Map<String, Object> company(String code) {
        return companyRow(MemoryRules.code(code));
    }

    @Override
    public List<Map<String, Object>> companies(String query) {
        String pattern = "%" + (query == null ? "" : query.strip()) + "%";
        return jdbc.query(
                "SELECT * FROM company_memory WHERE code LIKE ? OR name LIKE ? "
                        + "ORDER BY updated_at DESC, code ASC LIMIT ?",
                (rs, row) -> companySummary(rs),
                pattern,
                pattern,
                100);
    }

    @Override
    public Map<String, Object> detail(String code, String status, Integer limit) {
        String key = MemoryRules.code(code);
        Map<String, Object> company = companyRow(key);
        if (company == null) {
            return null;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("company", company);
        @SuppressWarnings("unchecked")
        Map<String, Object> counts = (Map<String, Object>) company.get("counts");
        result.put("counts", counts);
        for (String section : MemoryRules.SECTIONS) {
            String[] table = TABLES.get(section);
            List<Object> params = new ArrayList<>();
            StringBuilder sql =
                    new StringBuilder("SELECT * FROM ").append(table[0]).append(" WHERE code = ?");
            params.add(key);
            if (status != null && !status.isEmpty()) {
                sql.append(" AND ").append(table[2]).append(" = ?");
                params.add(status);
            }
            sql.append(" ORDER BY ").append(table[3]);
            if (limit != null) {
                sql.append(" LIMIT ?");
                params.add(Math.max(1, limit));
            }
            result.put(section, rows(sql.toString(), mapper(section), params.toArray()));
        }
        return result;
    }

    @Override
    public Map<String, Object> read(
            String code, String section, String recordId, String status, int limit, int offset) {
        String key = MemoryRules.code(code);
        if (!MemoryRules.SECTIONS.contains(section)) {
            throw new MemoryException("未知模块：" + section);
        }
        String[] table = TABLES.get(section);
        Map<String, Object> company = companyRow(key);
        if (company == null) {
            throw new MemoryException("尚未建档");
        }
        List<Object> params = new ArrayList<>();
        params.add(key);
        StringBuilder where = new StringBuilder("code = ?");
        if (recordId != null && !recordId.isEmpty()) {
            where.append(" AND ").append(table[1]).append(" = ?");
            params.add(recordId);
        }
        if (status != null && !status.isEmpty()) {
            where.append(" AND ").append(table[2]).append(" = ?");
            params.add(status);
        }
        Integer total =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM " + table[0] + " WHERE " + where,
                        Integer.class,
                        params.toArray());
        List<Object> page = new ArrayList<>(params);
        page.add(Math.max(1, Math.min(limit, 500)));
        page.add(Math.max(0, offset));
        List<Map<String, Object>> records =
                rows(
                        "SELECT * FROM " + table[0] + " WHERE " + where
                                + " ORDER BY " + table[3] + " LIMIT ? OFFSET ?",
                        mapper(section),
                        page.toArray());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("company", company);
        result.put("section", section);
        result.put("total", total == null ? 0 : total);
        result.put("records", records);
        return result;
    }

    @Override
    public Map<String, Object> append(
            String code, String section, Map<String, Object> record, String name) {
        String key = MemoryRules.code(code);
        if (!MemoryRules.SECTIONS.contains(section)) {
            throw new MemoryException("未知模块：" + section);
        }
        if (section.equals("changes")) {
            throw new MemoryException("changes 只能通过 record_conclusion_change 写入");
        }
        return section.equals("facts")
                ? appendFact(key, record, name)
                : appendSection(key, section, record == null ? Map.of() : record, name);
    }

    @Override
    public Map<String, Object> update(
            String code, String section, String recordId, Map<String, Object> patch) {
        String key = MemoryRules.code(code);
        if (!MemoryRules.SECTIONS.contains(section)) {
            throw new MemoryException("未知模块：" + section);
        }
        Set<String> allowed = UPDATE_ALLOWLISTS.get(section);
        if (allowed == null) {
            throw new MemoryException(section + " 不允许直接更新");
        }
        if (patch == null || patch.isEmpty()) {
            throw new MemoryException("patch 不能为空");
        }
        List<String> unknown = new ArrayList<>();
        for (String field : patch.keySet()) {
            if (!allowed.contains(field)) {
                unknown.add(field);
            }
        }
        if (!unknown.isEmpty()) {
            unknown.sort(String::compareTo);
            throw new MemoryException("不允许更新字段：" + String.join("、", unknown));
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : patch.entrySet()) {
            String field = entry.getKey();
            normalized.put(
                    field,
                    switch (field) {
                        case "verification_status" ->
                                MemoryRules.enumOf(
                                        entry.getValue(), field, MemoryRules.VERIFICATION_STATUSES);
                        case "status" ->
                                MemoryRules.enumOf(
                                        entry.getValue(),
                                        field,
                                        section.equals("hypotheses")
                                                ? MemoryRules.HYPOTHESIS_STATUSES
                                                : MemoryRules.QUESTION_STATUSES);
                        case "confidence" ->
                                MemoryRules.enumOf(
                                        entry.getValue(), field, MemoryRules.CONFIDENCE_LEVELS);
                        default -> MemoryRules.text(entry.getValue(), field, false);
                    });
        }
        String[] table = TABLES.get(section);
        Map<String, Object> updated =
                tx.execute(
                        state -> {
                            if (!exists("company_memory", "code", key)) {
                                throw new MemoryException("尚未建档");
                            }
                            if (!existsRecord(table[0], table[1], key, recordId)) {
                                throw new MemoryException("记录不存在：" + recordId);
                            }
                            String now = now();
                            List<Object> args = new ArrayList<>(normalized.values());
                            args.add(now);
                            args.add(key);
                            args.add(recordId);
                            StringBuilder sql = new StringBuilder("UPDATE ").append(table[0]).append(" SET ");
                            String assignments =
                                    String.join(", ", normalized.keySet().stream().map(k -> k + " = ?").toList());
                            sql.append(assignments).append(", updated_at = ? WHERE code = ? AND ")
                                    .append(table[1]).append(" = ?");
                            jdbc.update(sql.toString(), args.toArray());
                            touchCompany(key, now, false);
                            List<Map<String, Object>> found =
                                    rows(
                                            "SELECT * FROM " + table[0] + " WHERE code = ? AND "
                                                    + table[1] + " = ?",
                                            mapper(section),
                                            key,
                                            recordId);
                            return found.get(0);
                        });
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("record", updated);
        result.put("counts", counts(key));
        return result;
    }

    @Override
    public Map<String, Object> change(String code, Map<String, Object> payload) {
        String key = MemoryRules.code(code);
        if (payload == null) {
            throw new MemoryException("payload 必须是对象");
        }
        String operation =
                MemoryRules.enumOf(payload.get("operation"), "operation", MemoryRules.CHANGE_OPERATIONS);
        String conclusionId = MemoryRules.text(payload.get("conclusion_id"), "conclusion_id", true);
        String oldConclusion = MemoryRules.text(payload.get("old_conclusion"), "old_conclusion", true);
        String newConclusion = MemoryRules.text(payload.get("new_conclusion"), "new_conclusion", true);
        String triggerEvent = MemoryRules.text(payload.get("trigger_event"), "trigger_event", true);
        String triggerDate = MemoryRules.text(payload.get("trigger_date"), "trigger_date", true);
        String reason = MemoryRules.text(payload.get("reason"), "reason", true);
        String impact = MemoryRules.text(payload.get("impact"), "impact", true);
        String reviewStatus = MemoryRules.text(payload.get("review_status"), "review_status", false);
        String newTitle = MemoryRules.text(payload.get("new_title"), "new_title", false);
        String confidence =
                MemoryRules.enumOf(payload.get("confidence"), "confidence", MemoryRules.CONFIDENCE_LEVELS, "");
        List<String> evidenceIds =
                MemoryRules.refIds(payload.get("evidence_ids"), "evidence_ids", MemoryRules.EVIDENCE_ID);
        List<String> basedFacts =
                MemoryRules.refIds(payload.get("based_on_fact_ids"), "based_on_fact_ids", MemoryRules.FACT_ID);
        List<String> basedHypos =
                MemoryRules.refIds(
                        payload.get("based_on_hypothesis_ids"),
                        "based_on_hypothesis_ids",
                        MemoryRules.HYPOTHESIS_ID);
        if (evidenceIds.isEmpty()) {
            throw new MemoryException("变化必须附至少一条证据");
        }
        if (operation.equals("维持")) {
            if (!newConclusion.equals(oldConclusion)) {
                throw new MemoryException("维持时新旧结论必须相同");
            }
        } else if (newConclusion.equals(oldConclusion)) {
            throw new MemoryException("补充/修正/推翻的新结论必须区别于旧结论");
        }
        if (operation.equals("推翻") && newTitle.isEmpty()) {
            throw new MemoryException("推翻必须提供 new_title");
        }
        List<String> factEvidence = filterPrefix(evidenceIds, "F");
        List<String> hypothesisEvidence = filterPrefix(evidenceIds, "H");

        String changeId =
                tx.execute(
                        state -> {
                            if (!exists("company_memory", "code", key)) {
                                throw new MemoryException("尚未建档");
                            }
                            List<Map<String, Object>> currentRows =
                                    rows(
                                            "SELECT * FROM memory_conclusion WHERE code = ? AND conclusion_id = ?",
                                            ResearchMemoryStore::conclusionRow,
                                            key,
                                            conclusionId);
                            if (currentRows.isEmpty()) {
                                throw new MemoryException("结论不存在：" + conclusionId);
                            }
                            Map<String, Object> current = currentRows.get(0);
                            if (!"有效".equals(current.get("status"))) {
                                throw new MemoryException(
                                        "结论已推翻，不能继续变更：" + conclusionId + "，请在当前有效结论上记录");
                            }
                            if (!oldConclusion.equals(current.get("statement"))) {
                                throw new MemoryException("old_conclusion 与档案中的当前结论不一致");
                            }
                            requireIds(key, "memory_fact", "fact_id", factEvidence, "变化引用了不存在的事实：");
                            requireIds(key, "memory_hypothesis", "hypothesis_id", hypothesisEvidence, "变化引用了不存在的假设：");
                            requireIds(key, "memory_fact", "fact_id", basedFacts, "新结论引用了不存在的事实：");
                            requireIds(key, "memory_hypothesis", "hypothesis_id", basedHypos, "新结论引用了不存在的假设：");

                            String id = nextId(key, "memory_change", "change_id", "C");
                            String now = now();
                            List<String> facts;
                            List<String> hypos;
                            Map<String, Object> from;
                            Map<String, Object> to;
                            if (operation.equals("推翻")) {
                                String newId = nextId(key, "memory_conclusion", "conclusion_id", "G");
                                facts = mergeIds(List.of(), basedFacts.isEmpty() ? factEvidence : basedFacts);
                                hypos = mergeIds(List.of(), basedHypos.isEmpty() ? hypothesisEvidence : basedHypos);
                                jdbc.update(
                                        "INSERT INTO memory_conclusion (code, conclusion_id, title, statement, "
                                                + "status, confidence, based_on_fact_ids_json, "
                                                + "based_on_hypothesis_ids_json, last_change_id, created_at, updated_at) "
                                                + "VALUES (?, ?, ?, ?, '有效', ?, ?, ?, ?, ?, ?)",
                                        key, newId, newTitle, newConclusion, confidence,
                                        json(facts), json(hypos), id, now, now);
                                jdbc.update(
                                        "UPDATE memory_conclusion SET status = '已推翻', last_change_id = ?, "
                                                + "updated_at = ? WHERE code = ? AND conclusion_id = ?",
                                        id, now, key, conclusionId);
                                from = conclusion(key, conclusionId);
                                to = conclusion(key, newId);
                            } else {
                                @SuppressWarnings("unchecked")
                                List<String> currentFacts = (List<String>) current.get("based_on_fact_ids");
                                @SuppressWarnings("unchecked")
                                List<String> currentHypos = (List<String>) current.get("based_on_hypothesis_ids");
                                facts = mergeIds(currentFacts, concat(basedFacts, factEvidence));
                                hypos = mergeIds(currentHypos, concat(basedHypos, hypothesisEvidence));
                                String statement =
                                        operation.equals("维持")
                                                ? (String) current.get("statement")
                                                : newConclusion;
                                jdbc.update(
                                        "UPDATE memory_conclusion SET statement = ?, "
                                                + "based_on_fact_ids_json = ?, based_on_hypothesis_ids_json = ?, "
                                                + "last_change_id = ?, updated_at = ? WHERE code = ? AND conclusion_id = ?",
                                        statement, json(facts), json(hypos), id, now, key, conclusionId);
                                from = conclusion(key, conclusionId);
                                to = from;
                            }
                            jdbc.update(
                                    "INSERT INTO memory_change (code, change_id, from_conclusion_id, "
                                            + "to_conclusion_id, operation, old_conclusion, new_conclusion, "
                                            + "trigger_event, trigger_date, evidence_ids_json, reason, impact, "
                                            + "review_status, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                                    key, id, from.get("conclusion_id"), to.get("conclusion_id"),
                                    operation, oldConclusion, newConclusion, triggerEvent, triggerDate,
                                    json(evidenceIds), reason, impact, reviewStatus, now);
                            touchCompany(key, now, true);
                            return id;
                        });

        Map<String, Object> change =
                rows(
                                "SELECT * FROM memory_change WHERE code = ? AND change_id = ?",
                                ResearchMemoryStore::changeRow,
                                key,
                                changeId)
                        .get(0);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("change", change);
        result.put("from", conclusion(key, (String) change.get("from_conclusion_id")));
        result.put("to", conclusion(key, (String) change.get("to_conclusion_id")));
        result.put("counts", counts(key));
        return result;
    }

    /** 从旧库逐行复制，业务 id 与时间戳原样保留；返回新增行数。 */
    public int importLegacy(Path legacyPath) {
        JdbcTemplate legacy =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                "jdbc:sqlite:" + legacyPath.toAbsolutePath().toString().replace('\\', '/')));
        return tx.execute(
                state -> {
                    int inserted = 0;
                    for (String table :
                            List.of("company_memory", "memory_fact", "memory_hypothesis",
                                    "memory_conclusion", "memory_question", "memory_change")) {
                        String columns = IMPORT_COLUMNS.get(table);
                        List<String> columnNames = List.of(columns.split(","));
                        List<Map<String, Object>> rows =
                                legacy.queryForList("SELECT " + columns + " FROM " + table);
                        String placeholders =
                                String.join(", ", java.util.Collections.nCopies(columnNames.size(), "?"));
                        for (Map<String, Object> row : rows) {
                            inserted +=
                                    jdbc.update(
                                            "INSERT OR IGNORE INTO " + table + " (" + columns + ") VALUES ("
                                                    + placeholders + ")",
                                            columnNames.stream().map(row::get).toArray());
                        }
                    }
                    return inserted;
                });
    }

    // ---- 写入分节 ----

    private Map<String, Object> appendFact(String code, Map<String, Object> record, String name) {
        String companyName = name == null ? "" : name;
        Map<String, Object> fact = MemoryRules.validateFact(record);
        String dedupeKey = MemoryRules.dedupeKey(fact);
        Object[] outcome =
                tx.execute(
                        state -> {
                            Map<String, Object> company =
                                    ensureCompany(
                                            code,
                                            companyName.isEmpty()
                                                    ? (String) fact.get("subject")
                                                    : companyName);
                            List<Map<String, Object>> existing =
                                    rows(
                                            "SELECT * FROM memory_fact WHERE code = ? AND dedupe_key = ?",
                                            ResearchMemoryStore::factRow,
                                            code,
                                            dedupeKey);
                            if (!existing.isEmpty()) {
                                return new Object[] {existing.get(0), false};
                            }
                            String factId = nextId(code, "memory_fact", "fact_id", "F");
                            String now = now();
                            String subject = (String) fact.get("subject");
                            if (subject.isEmpty()) {
                                subject = (String) company.get("name");
                            }
                            try {
                                jdbc.update(
                                        "INSERT INTO memory_fact (code, fact_id, subject, fact_type, content, "
                                                + "metrics_json, report_period, published_at, source_id, source_label, "
                                                + "source_locator, source_url, verification_status, dedupe_key, created_at, updated_at) "
                                                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                                        code, factId, subject, fact.get("fact_type"), fact.get("content"),
                                        json(fact.get("metrics")), fact.get("report_period"), fact.get("published_at"),
                                        fact.get("source_id"), fact.get("source_label"), fact.get("source_locator"),
                                        fact.get("source_url"), fact.get("verification_status"), dedupeKey, now, now);
                            } catch (org.springframework.dao.DuplicateKeyException duplicate) {
                                // 并发重复：另一个请求已插入同一 dedupe_key。
                                return new Object[] {
                                    rows(
                                                    "SELECT * FROM memory_fact WHERE code = ? AND dedupe_key = ?",
                                                    ResearchMemoryStore::factRow,
                                                    code,
                                                    dedupeKey)
                                            .get(0),
                                    false
                                };
                            }
                            touchCompany(code, now, false);
                            return new Object[] {fact(code, factId), true};
                        });
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("record", outcome[0]);
        result.put("created", outcome[1]);
        result.put("counts", counts(code));
        return result;
    }

    private Map<String, Object> appendSection(
            String code, String section, Map<String, Object> record, String name) {
        Map<String, Object> data;
        String table;
        String idColumn;
        String prefix;
        String sql;
        List<String> refFacts;
        List<String> refHypotheses;
        String factRefMessage;
        String hypothesisRefMessage;
        if (section.equals("hypotheses")) {
            data = MemoryRules.validateHypothesis(record);
            table = "memory_hypothesis";
            idColumn = "hypothesis_id";
            prefix = "H";
            sql =
                    "INSERT INTO memory_hypothesis (code, hypothesis_id, statement, "
                            + "supporting_fact_ids_json, assumptions, confidence, verification_method, "
                            + "falsification_condition, status, next_review_date, created_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
            refFacts = castList(data.get("supporting_fact_ids"));
            refHypotheses = List.of();
            factRefMessage = "假设引用了不存在的事实：";
            hypothesisRefMessage = "";
        } else if (section.equals("questions")) {
            data = MemoryRules.validateQuestion(record);
            table = "memory_question";
            idColumn = "question_id";
            prefix = "Q";
            sql =
                    "INSERT INTO memory_question (code, question_id, question, importance, "
                            + "known_facts_json, missing_evidence, next_action, resolution_criteria, "
                            + "related_hypothesis_ids_json, status, last_checked_at, resolution_note, "
                            + "created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
            refFacts = castList(data.get("known_facts"));
            refHypotheses = castList(data.get("related_hypothesis_ids"));
            factRefMessage = "问题引用了不存在的事实：";
            hypothesisRefMessage = "问题引用了不存在的假设：";
        } else {
            data = MemoryRules.validateConclusion(record);
            table = "memory_conclusion";
            idColumn = "conclusion_id";
            prefix = "G";
            sql =
                    "INSERT INTO memory_conclusion (code, conclusion_id, title, statement, "
                            + "status, confidence, based_on_fact_ids_json, based_on_hypothesis_ids_json, "
                            + "created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
            refFacts = castList(data.get("based_on_fact_ids"));
            refHypotheses = castList(data.get("based_on_hypothesis_ids"));
            factRefMessage = "结论引用了不存在的事实：";
            hypothesisRefMessage = "结论引用了不存在的假设：";
        }
        String finalTable = table;
        String finalIdColumn = idColumn;
        String finalPrefix = prefix;
        String finalSql = sql;
        String finalFactRefMessage = factRefMessage;
        String finalHypothesisRefMessage = hypothesisRefMessage;
        Map<String, Object> appended =
                tx.execute(
                        state -> {
                            ensureCompany(code, name);
                            requireIds(code, "memory_fact", "fact_id", refFacts, finalFactRefMessage);
                            requireIds(code, "memory_hypothesis", "hypothesis_id", refHypotheses, finalHypothesisRefMessage);
                            String recordId = nextId(code, finalTable, finalIdColumn, finalPrefix);
                            String now = now();
                            if (section.equals("hypotheses")) {
                                jdbc.update(
                                        finalSql, code, recordId, data.get("statement"),
                                        json(data.get("supporting_fact_ids")), data.get("assumptions"),
                                        data.get("confidence"), data.get("verification_method"),
                                        data.get("falsification_condition"), data.get("status"),
                                        data.get("next_review_date"), now, now);
                            } else if (section.equals("questions")) {
                                jdbc.update(
                                        finalSql, code, recordId, data.get("question"),
                                        data.get("importance"), json(data.get("known_facts")),
                                        data.get("missing_evidence"), data.get("next_action"),
                                        data.get("resolution_criteria"),
                                        json(data.get("related_hypothesis_ids")), data.get("status"),
                                        null, data.get("resolution_note"), now, now);
                            } else {
                                jdbc.update(
                                        finalSql, code, recordId, data.get("title"),
                                        data.get("statement"), data.get("status"),
                                        data.get("confidence"), json(data.get("based_on_fact_ids")),
                                        json(data.get("based_on_hypothesis_ids")), now, now);
                            }
                            touchCompany(code, now, false);
                            return rows(
                                            "SELECT * FROM " + finalTable + " WHERE code = ? AND "
                                                    + finalIdColumn + " = ?",
                                            mapper(section),
                                            code,
                                            recordId)
                                    .get(0);
                        });
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("record", appended);
        result.put("created", true);
        result.put("counts", counts(code));
        return result;
    }

    // ---- 查询辅助 ----

    private Map<String, Object> companyRow(String code) {
        List<Map<String, Object>> rows =
                jdbc.query(
                        "SELECT * FROM company_memory WHERE code = ?",
                        (rs, row) -> companySummary(rs),
                        code);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Map<String, Object> companySummary(ResultSet rs) throws SQLException {
        Map<String, Object> result = new LinkedHashMap<>();
        String code = rs.getString("code");
        result.put("code", code);
        result.put("name", rs.getString("name"));
        result.put("created_at", rs.getString("created_at"));
        result.put("updated_at", rs.getString("updated_at"));
        result.put("last_reviewed_at", rs.getString("last_reviewed_at"));
        result.put("counts", counts(code));
        result.put(
                "conclusion",
                jdbc.query(
                                "SELECT conclusion_id, status, statement FROM memory_conclusion "
                                        + "WHERE code = ? AND status = '有效' ORDER BY updated_at DESC, id DESC LIMIT 1",
                                ResearchMemoryStore::conclusionSummary,
                                code)
                        .stream()
                        .findFirst()
                        .orElse(""));
        return result;
    }

    private static String conclusionSummary(ResultSet rs, int row) throws SQLException {
        String statement = rs.getString("statement");
        return rs.getString("conclusion_id") + " [" + rs.getString("status") + "] "
                + statement.substring(0, Math.min(60, statement.length()));
    }

    private Map<String, Object> counts(String code) {
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("facts", count("memory_fact", code, ""));
        counts.put("hypotheses", count("memory_hypothesis", code, ""));
        counts.put("conclusions", count("memory_conclusion", code, ""));
        counts.put("questions", count("memory_question", code, ""));
        counts.put(
                "open_questions",
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM memory_question WHERE code = ? AND status IN (?, ?)",
                        Integer.class,
                        code,
                        MemoryRules.OPEN_QUESTION_STATUSES.get(0),
                        MemoryRules.OPEN_QUESTION_STATUSES.get(1)));
        counts.put("changes", count("memory_change", code, ""));
        return counts;
    }

    private int count(String table, String code, String extra) {
        Integer value =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM " + table + " WHERE code = ?" + extra,
                        Integer.class,
                        code);
        return value == null ? 0 : value;
    }

    private boolean exists(String table, String column, String key) {
        return !jdbc.query("SELECT 1 FROM " + table + " WHERE " + column + " = ?", (rs, row) -> 1, key)
                .isEmpty();
    }

    private boolean existsRecord(String table, String idColumn, String code, String recordId) {
        return !jdbc.query(
                        "SELECT 1 FROM " + table + " WHERE code = ? AND " + idColumn + " = ?",
                        (rs, row) -> 1,
                        code,
                        recordId)
                .isEmpty();
    }

    private void requireIds(
            String code, String table, String column, List<String> ids, String message) {
        if (ids.isEmpty()) {
            return;
        }
        List<String> missing = new ArrayList<>();
        for (String id : ids) {
            Integer found =
                    jdbc.queryForObject(
                            "SELECT COUNT(*) FROM " + table + " WHERE code = ? AND " + column + " = ?",
                            Integer.class,
                            code,
                            id);
            if (found == null || found == 0) {
                missing.add(id);
            }
        }
        if (!missing.isEmpty()) {
            throw new MemoryException(message + String.join("、", missing));
        }
    }

    private String nextId(String code, String table, String column, String prefix) {
        Integer max =
                jdbc.queryForObject(
                        "SELECT MAX(CAST(SUBSTR(" + column + ", 2) AS INTEGER)) FROM " + table
                                + " WHERE code = ?",
                        Integer.class,
                        code);
        return prefix + String.format("%03d", (max == null ? 0 : max) + 1);
    }

    private Map<String, Object> ensureCompany(String code, String name) {
        Map<String, Object> row = companyRow(code);
        String now = now();
        String trimmed = name == null ? "" : name.strip();
        if (row == null) {
            jdbc.update(
                    "INSERT INTO company_memory (code, name, created_at, updated_at, last_reviewed_at) "
                            + "VALUES (?, ?, ?, ?, NULL)",
                    code,
                    trimmed.isEmpty() ? code : trimmed,
                    now,
                    now);
            return companyRow(code);
        }
        if (!trimmed.isEmpty() && String.valueOf(row.get("name")).strip().isEmpty()) {
            jdbc.update(
                    "UPDATE company_memory SET name = ?, updated_at = ? WHERE code = ?",
                    trimmed,
                    now,
                    code);
            return companyRow(code);
        }
        return row;
    }

    private void touchCompany(String code, String now, boolean reviewed) {
        if (reviewed) {
            jdbc.update(
                    "UPDATE company_memory SET last_reviewed_at = ?, updated_at = ? WHERE code = ?",
                    now,
                    now,
                    code);
        } else {
            jdbc.update("UPDATE company_memory SET updated_at = ? WHERE code = ?", now, code);
        }
    }

    private Map<String, Object> conclusion(String code, String id) {
        return rows(
                        "SELECT * FROM memory_conclusion WHERE code = ? AND conclusion_id = ?",
                        ResearchMemoryStore::conclusionRow,
                        code,
                        id)
                .get(0);
    }

    private Map<String, Object> fact(String code, String id) {
        return rows(
                        "SELECT * FROM memory_fact WHERE code = ? AND fact_id = ?",
                        ResearchMemoryStore::factRow,
                        code,
                        id)
                .get(0);
    }

    private List<Map<String, Object>> rows(
            String sql, RowMapper<Map<String, Object>> mapper, Object... params) {
        return jdbc.query(sql, mapper, params);
    }

    private static RowMapper<Map<String, Object>> mapper(String section) {
        return switch (section) {
            case "facts" -> ResearchMemoryStore::factRow;
            case "hypotheses" -> ResearchMemoryStore::hypothesisRow;
            case "conclusions" -> ResearchMemoryStore::conclusionRow;
            case "questions" -> ResearchMemoryStore::questionRow;
            default -> ResearchMemoryStore::changeRow;
        };
    }

    private static Map<String, Object> factRow(ResultSet rs, int row) throws SQLException {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("fact_id", rs.getString("fact_id"));
        result.put("subject", rs.getString("subject"));
        result.put("fact_type", rs.getString("fact_type"));
        result.put("content", rs.getString("content"));
        result.put("metrics", list(rs.getString("metrics_json")));
        result.put("report_period", rs.getString("report_period"));
        result.put("published_at", rs.getString("published_at"));
        result.put("source_id", rs.getString("source_id"));
        result.put("source_label", rs.getString("source_label"));
        result.put("source_locator", rs.getString("source_locator"));
        result.put("source_url", rs.getString("source_url"));
        result.put("verification_status", rs.getString("verification_status"));
        result.put("created_at", rs.getString("created_at"));
        result.put("updated_at", rs.getString("updated_at"));
        return result;
    }

    private static Map<String, Object> hypothesisRow(ResultSet rs, int row) throws SQLException {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("hypothesis_id", rs.getString("hypothesis_id"));
        result.put("statement", rs.getString("statement"));
        result.put("supporting_fact_ids", list(rs.getString("supporting_fact_ids_json")));
        result.put("assumptions", rs.getString("assumptions"));
        result.put("confidence", rs.getString("confidence"));
        result.put("verification_method", rs.getString("verification_method"));
        result.put("falsification_condition", rs.getString("falsification_condition"));
        result.put("status", rs.getString("status"));
        result.put("next_review_date", rs.getString("next_review_date"));
        result.put("created_at", rs.getString("created_at"));
        result.put("updated_at", rs.getString("updated_at"));
        return result;
    }

    private static Map<String, Object> conclusionRow(ResultSet rs, int row) throws SQLException {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("conclusion_id", rs.getString("conclusion_id"));
        result.put("title", rs.getString("title"));
        result.put("statement", rs.getString("statement"));
        result.put("status", rs.getString("status"));
        result.put("confidence", rs.getString("confidence"));
        result.put("based_on_fact_ids", list(rs.getString("based_on_fact_ids_json")));
        result.put("based_on_hypothesis_ids", list(rs.getString("based_on_hypothesis_ids_json")));
        result.put("last_change_id", rs.getString("last_change_id"));
        result.put("created_at", rs.getString("created_at"));
        result.put("updated_at", rs.getString("updated_at"));
        return result;
    }

    private static Map<String, Object> questionRow(ResultSet rs, int row) throws SQLException {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("question_id", rs.getString("question_id"));
        result.put("question", rs.getString("question"));
        result.put("importance", rs.getString("importance"));
        result.put("known_facts", list(rs.getString("known_facts_json")));
        result.put("missing_evidence", rs.getString("missing_evidence"));
        result.put("next_action", rs.getString("next_action"));
        result.put("resolution_criteria", rs.getString("resolution_criteria"));
        result.put("related_hypothesis_ids", list(rs.getString("related_hypothesis_ids_json")));
        result.put("status", rs.getString("status"));
        result.put("last_checked_at", rs.getString("last_checked_at"));
        result.put("resolution_note", rs.getString("resolution_note"));
        result.put("created_at", rs.getString("created_at"));
        result.put("updated_at", rs.getString("updated_at"));
        return result;
    }

    private static Map<String, Object> changeRow(ResultSet rs, int row) throws SQLException {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("change_id", rs.getString("change_id"));
        result.put("from_conclusion_id", rs.getString("from_conclusion_id"));
        result.put("to_conclusion_id", rs.getString("to_conclusion_id"));
        result.put("operation", rs.getString("operation"));
        result.put("old_conclusion", rs.getString("old_conclusion"));
        result.put("new_conclusion", rs.getString("new_conclusion"));
        result.put("trigger_event", rs.getString("trigger_event"));
        result.put("trigger_date", rs.getString("trigger_date"));
        result.put("evidence_ids", list(rs.getString("evidence_ids_json")));
        result.put("reason", rs.getString("reason"));
        result.put("impact", rs.getString("impact"));
        result.put("review_status", rs.getString("review_status"));
        result.put("created_at", rs.getString("created_at"));
        return result;
    }

    private static List<Object> list(String raw) {
        if (raw == null || raw.isEmpty()) {
            return new ArrayList<>();
        }
        try {
            List<Object> items = MAPPER.readValue(raw, new TypeReference<List<Object>>() {});
            return items == null ? new ArrayList<>() : items;
        } catch (Exception error) {
            return new ArrayList<>();
        }
    }

    private static String json(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception error) {
            throw new MemoryException("记录无法序列化：" + error.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> castList(Object value) {
        return value instanceof List<?> items ? (List<String>) items : List.of();
    }

    private static List<String> filterPrefix(List<String> ids, String prefix) {
        return ids.stream().filter(id -> id.startsWith(prefix)).toList();
    }

    private static List<String> concat(List<String> first, List<String> second) {
        List<String> result = new ArrayList<>(first);
        result.addAll(second);
        return result;
    }

    private static List<String> mergeIds(List<String> existing, List<String> extra) {
        List<String> merged = new ArrayList<>();
        for (String item : concat(existing, extra)) {
            if (!merged.contains(item)) {
                merged.add(item);
            }
        }
        return merged;
    }

    private String now() {
        return LocalDateTime.now(clock).format(NOW);
    }
}
