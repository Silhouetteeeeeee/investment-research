package com.investmentresearch.data.adapter.eastmoney;

import com.investmentresearch.core.domain.Source;
import com.investmentresearch.core.domain.ToolResult;
import com.investmentresearch.core.port.ResearchNotesPort;
import com.investmentresearch.core.port.ResearchPort;
import com.investmentresearch.data.persistence.ResearchCacheStore;
import com.investmentresearch.data.upstream.UpstreamClient;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

/**
 * 调研纪要原文，照抄 {@code stock/research.py:build_research_notes} 与
 * {@code assistant/tools.py:_research_notes}。主体数据复用机构调研的结果。
 */
@Repository
public class ResearchNotesAdapter implements ResearchNotesPort {
    static final String REPORT_NAME = "RPT_ORG_SURVEY";
    static final String PAGE_URL = "https://data.eastmoney.com/jgdy/";
    static final double NOTE_MAX_AGE_HOURS = 24 * 30;
    static final int MAX_NOTE_CHARS = 20_000;
    static final int LEAD_CLIP = 1_200;
    static final int ANSWER_CLIP = 2_500;

    private static final Pattern QUESTION_MARK = Pattern.compile("(?=问\\s*[：:])");
    private static final Pattern QUESTION_OF =
            Pattern.compile("^问\\s*[：:]\\s*(.*?)(?=答\\s*[：:]|\\z)", Pattern.DOTALL);
    private static final Pattern ANSWER_OF =
            Pattern.compile("答\\s*[：:]\\s*(.*)", Pattern.DOTALL);
    private static final Pattern SPACES = Pattern.compile("(?U)\\s+");
    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ResearchCacheStore store;
    private final ResearchPort research;
    private final UpstreamClient upstream;
    private final String endpoint;
    private final Clock clock;

    @Autowired
    public ResearchNotesAdapter(
            ResearchCacheStore store,
            ResearchPort research,
            UpstreamClient upstream,
            @Value("${app.datacenter-url:https://datacenter-web.eastmoney.com/api/data/v1/get}")
                    String endpoint) {
        this(store, research, upstream, endpoint, Clock.systemDefaultZone());
    }

    ResearchNotesAdapter(
            ResearchCacheStore store,
            ResearchPort research,
            UpstreamClient upstream,
            String endpoint,
            Clock clock) {
        this.store = store;
        this.research = research;
        this.upstream = upstream;
        this.endpoint = endpoint;
        this.clock = clock;
    }

    @Override
    public ToolResult researchNotes(String code, int years, int events, int maxChars) {
        try {
            ToolResult base = research.research(code, years);
            if (!base.ok()) {
                return base;
            }
            Map<String, Object> baseData = base.data();
            List<Map<String, Object>> selected = new ArrayList<>();
            for (Object item : listOf(baseData.get("events"))) {
                if (selected.size() >= events) {
                    break;
                }
                selected.add(mapOf(item));
            }

            List<String> contents = new ArrayList<>();
            int withNote = 0;
            for (Map<String, Object> event : selected) {
                String content = ensureNote(code, text(event.get("date")), text(event.get("notice_date")));
                contents.add(content);
                if (content != null) {
                    withNote++;
                }
            }
            int share = withNote == 0 ? 0 : maxChars / withNote;

            List<Map<String, Object>> rows = new ArrayList<>();
            int questions = 0;
            int omitted = 0;
            int totalChars = 0;
            int returnedChars = 0;
            for (int index = 0; index < selected.size(); index++) {
                Map<String, Object> event = selected.get(index);
                String content = contents.get(index);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("date", event.get("date"));
                row.put("notice_date", event.get("notice_date"));
                row.put("place", event.get("place"));
                row.put("ways", event.getOrDefault("ways", List.of()));
                row.put("institution_count", event.get("institution_count"));
                row.put("has_note", content != null);
                row.put("lead", null);
                row.put("qa", List.of());
                row.put("text", null);
                row.put("total_chars", content == null ? 0 : content.length());
                row.put("truncated", false);
                row.put("omitted", 0);
                if (content != null) {
                    Map<String, Object> body = noteBody(content, share);
                    returnedChars += DatacenterSupport.intOf(body.get("returned_chars"));
                    row.put("lead", body.get("lead"));
                    row.put("qa", body.get("qa"));
                    row.put("text", body.get("text"));
                    row.put("truncated", body.get("truncated"));
                    row.put("omitted", body.get("omitted"));
                }
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> qa = (List<Map<String, Object>>) row.get("qa");
                questions += qa.size();
                omitted += DatacenterSupport.intOf(row.get("omitted"));
                totalChars += DatacenterSupport.intOf(row.get("total_chars"));
                rows.add(row);
            }

            Map<String, Object> counts = new LinkedHashMap<>();
            counts.put("events", rows.size());
            counts.put("events_with_notes", withNote);
            counts.put("questions", questions);
            counts.put("omitted", omitted);
            counts.put("total_chars", totalChars);
            counts.put("returned_chars", returnedChars);

            List<String> notes = new ArrayList<>();
            if (withNote == 0) {
                notes.add("最近几场没有取到纪要原文，可能公司未披露全文");
            } else {
                notes.add("纪要是公司整理披露的，问题经过筛选，回答不代表承诺");
                notes.add("原文按篇幅截取，可调大 max_chars 或减少 events 读取更多问答");
                notes.add("只读取最近 " + rows.size() + " 场已披露的纪要");
            }

            String name = text(baseData.get("name"));
            String asOf = text(baseData.get("as_of"));
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("code", code);
            data.put("name", name);
            data.put("as_of", asOf);
            data.put("years", years);
            data.put("budget", maxChars);
            data.put("events", rows);
            data.put("counts", counts);
            data.put("notes", notes);
            data.put("stale", Boolean.TRUE.equals(baseData.get("stale")));

            String shownAsOf = asOf == null ? "" : asOf;
            String shownName = name == null || name.isEmpty() ? code : name;
            Source source =
                    new Source(
                            shownAsOf.isEmpty()
                                    ? "research-notes:" + code
                                    : "research-notes:" + code + ":" + shownAsOf,
                            "调研纪要",
                            (shownName + " 调研纪要 " + shownAsOf).strip(),
                            shownAsOf,
                            base.sources().isEmpty() ? "" : base.sources().get(0).fetchedAt(),
                            PAGE_URL);
            return ToolResult.ok(code, asOf, List.of(source), data);
        } catch (RuntimeException error) {
            return ToolResult.failure(code, error.getMessage());
        }
    }

    // ---------------------------------------------------------------- 纪要缓存

    /** {@code stock/research.py:ensure_note}：30 天缓存，抓取失败回退旧缓存。 */
    private String ensureNote(String code, String receiveDate, String noticeDate) {
        if (noticeDate == null) {
            return null;
        }
        String key = (receiveDate == null ? noticeDate : receiveDate) + "|" + noticeDate;
        Optional<ResearchCacheStore.Row> cached = store.readNote(code, key);
        if (cached.isPresent()
                && DatacenterSupport.ageHours(cached.get().fetchedAt(), clock) <= NOTE_MAX_AGE_HOURS) {
            return cached.get().data();
        }
        String content = fetchNote(code, noticeDate, receiveDate);
        if (content != null && !content.isEmpty()) {
            store.writeNote(code, key, content, LocalDateTime.now(clock).format(TIMESTAMP));
            return content;
        }
        return cached.map(ResearchCacheStore.Row::data).orElse(null);
    }

    /** {@code _request_content}：纪要属锦上添花，失败就跳过。 */
    private String fetchNote(String code, String noticeDate, String receiveDate) {
        String filter = "(SECURITY_CODE=\"" + code + "\")(NOTICE_DATE='" + noticeDate + "')";
        if (receiveDate != null) {
            filter += "(RECEIVE_START_DATE='" + receiveDate + "')";
        }
        String content =
                DatacenterSupport.fetchContent(upstream, endpoint, REPORT_NAME, filter);
        return content == null
                ? null
                : content.substring(0, Math.min(MAX_NOTE_CHARS, content.length()));
    }

    // ---------------------------------------------------------------- 纪要切分

    /** {@code stock/research.py:_note_body}。 */
    static Map<String, Object> noteBody(String content, int budget) {
        List<Map<String, Object>> blocks = noteBlocks(content);
        String lead = null;
        if (!blocks.isEmpty() && blocks.get(0).get("question") == null) {
            lead = clipText(text(blocks.get(0).get("text")), Math.min(LEAD_CLIP, budget));
            blocks = blocks.subList(1, blocks.size());
        }
        List<Map<String, Object>> questions = new ArrayList<>();
        for (Map<String, Object> block : blocks) {
            if (block.get("question") != null) {
                questions.add(block);
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        if (questions.isEmpty()) {
            String oneLine = oneLine(content);
            String text = clipText(oneLine, budget);
            if (text == null) {
                text = "";
            }
            out.put("lead", null);
            out.put("qa", List.of());
            out.put("text", text);
            out.put("returned_chars", text.length());
            out.put("truncated", oneLine.length() > text.length());
            out.put("omitted", 0);
            return out;
        }

        int used = lead == null ? 0 : lead.length();
        List<Map<String, Object>> qa = new ArrayList<>();
        int omitted = 0;
        for (Map<String, Object> block : questions) {
            if (omitted > 0) {
                omitted++;
                continue;
            }
            String question = text(block.get("question"));
            int room = budget - used - question.length() - 4;
            if (room < 60) {
                omitted = 1;
                continue;
            }
            // Python _answer_of 缺答案时给空串；text() 会把空串折成 null，这里补回来。
            String body = text(block.get("text"));
            if (body == null) {
                body = "";
            }
            String answer = body.substring(0, Math.min(room, ANSWER_CLIP));
            Map<String, Object> pair = new LinkedHashMap<>();
            pair.put("question", question);
            pair.put("answer", answer);
            qa.add(pair);
            used += question.length() + answer.length() + 4;
        }
        out.put("lead", lead);
        out.put("qa", qa);
        out.put("text", null);
        out.put("returned_chars", used);
        out.put("truncated", omitted > 0);
        out.put("omitted", omitted);
        return out;
    }

    /** {@code _note_blocks}：问句去重，非问答段落保留为开场。 */
    private static List<Map<String, Object>> noteBlocks(String content) {
        List<Map<String, Object>> blocks = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String block : splitBlocks(content)) {
            String question = questionOf(block);
            if (question != null) {
                if (!seen.add(question)) {
                    continue;
                }
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("question", question);
                item.put("text", answerOf(block));
                blocks.add(item);
            } else {
                String text = oneLine(block);
                if (!text.isEmpty()) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("question", null);
                    item.put("text", text);
                    blocks.add(item);
                }
            }
        }
        return blocks;
    }

    /** {@code _split_blocks}：没有“问：”标记时退化为段落。 */
    private static List<String> splitBlocks(String content) {
        String text = content.replace("\r\n", "\n").replace("\r", "\n");
        List<String> blocks = splitKeeping(text, QUESTION_MARK);
        if (blocks.size() <= 1) {
            blocks = splitKeeping(text, Pattern.compile("\n(?U)\\s*\n"));
        }
        return blocks;
    }

    private static List<String> splitKeeping(String text, Pattern pattern) {
        List<String> blocks = new ArrayList<>();
        for (String block : pattern.split(text)) {
            String trimmed = block.strip();
            if (!trimmed.isEmpty()) {
                blocks.add(trimmed);
            }
        }
        return blocks;
    }

    private static String questionOf(String block) {
        Matcher matcher = QUESTION_OF.matcher(block);
        if (!matcher.find() || matcher.start() != 0) {
            return null;
        }
        String text = oneLine(matcher.group(1));
        if (text.isEmpty()) {
            return null;
        }
        return text.substring(0, Math.min(300, text.length()));
    }

    private static String answerOf(String block) {
        Matcher matcher = ANSWER_OF.matcher(block);
        return oneLine(matcher.find() ? matcher.group(1) : "");
    }

    // ---------------------------------------------------------------- 小工具

    private static String oneLine(String text) {
        return SPACES.matcher(text == null ? "" : text).replaceAll(" ").strip();
    }

    private static String clipText(String text, int limit) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        return text.length() <= limit ? text : text.substring(0, limit);
    }

    private static String text(Object value) {
        return DatacenterSupport.text(value);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> listOf(Object value) {
        return value instanceof List<?> list ? (List<Object>) list : List.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOf(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }
}
