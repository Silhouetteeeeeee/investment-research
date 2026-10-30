package com.investmentresearch.data.adapter.eastmoney;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.investmentresearch.core.domain.Source;
import com.investmentresearch.core.domain.ToolResult;
import com.investmentresearch.core.port.AnnouncementPort;
import com.investmentresearch.data.persistence.AnnouncementEntity;
import com.investmentresearch.data.persistence.AnnouncementMapper;
import com.investmentresearch.data.persistence.StockMapper;
import com.investmentresearch.data.upstream.UpstreamClient;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 东财公告：按页抓取 → 入库 → 读回 → 输出，解析照抄 {@code stock/announcements.py}。 */
@Repository
public class AnnouncementAdapter implements AnnouncementPort {
    static final int PAGE_SIZE = 100;
    static final int MAX_PAGES = 5;
    static final int RETENTION_DAYS = 400;
    static final int MAX_AGE_HOURS = 6;
    static final int TOOL_ITEMS = 20;
    static final int DEFAULT_DAYS = 180;
    static final int MIN_DAYS = 1;
    static final int MAX_DAYS = 365;
    static final String DEFAULT_ENDPOINT =
            "https://np-anotice-stock.eastmoney.com/api/security/ann";
    private static final String DETAIL_URL =
            "https://data.eastmoney.com/notices/detail/%s/%s.html";
    private static final String SOURCE_LABEL = "公告";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    // 第一条是标签，其余是关键词；先判断利空，再判断利好
    private static final String[][] WARNING_RULES = {
        {"减持", "减持"},
        {"诉讼处罚", "诉讼", "仲裁", "处罚", "立案", "问询", "警示函"},
        {"风险警示", "退市风险", "风险警示", "停牌"},
        {"质押冻结", "质押", "冻结", "司法拍卖"},
        {"业绩预减", "预亏", "预减", "业绩下滑"},
    };
    private static final String[][] INFO_RULES = {
        {"业绩预告", "业绩预告", "业绩快报", "预增", "扭亏"},
        {"回购", "回购"},
        {"增持", "增持"},
        {"分红", "权益分派", "利润分配", "分红", "分配方案"},
        {"重大合同", "重大合同", "中标", "框架协议"},
        {"股权激励", "股权激励", "员工持股", "股票期权"},
        {"并购重组", "重大资产重组", "收购", "重组"},
    };
    private static final Pattern COMPANY_PREFIX =
            Pattern.compile("^(?!关于)[^。；：]{2,30}?(?:股份有限公司|有限责任公司)");
    private static final Comparator<AnnouncementEntity> NEWEST_FIRST =
            Comparator.comparing(AnnouncementEntity::getNoticeDate)
                    .thenComparing(
                            row -> row.getArtCode() == null ? "" : row.getArtCode())
                    .reversed();

    private final AnnouncementMapper mapper;
    private final StockMapper stockMapper;
    private final UpstreamClient upstreamClient;
    private final String endpoint;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    @Autowired
    public AnnouncementAdapter(
            AnnouncementMapper mapper,
            StockMapper stockMapper,
            UpstreamClient upstreamClient,
            @Value("${app.announcement-url:" + DEFAULT_ENDPOINT + "}") String endpoint,
            PlatformTransactionManager transactionManager) {
        this(
                mapper,
                stockMapper,
                upstreamClient,
                endpoint,
                Clock.systemDefaultZone(),
                transactionManager);
    }

    AnnouncementAdapter(
            AnnouncementMapper mapper,
            StockMapper stockMapper,
            UpstreamClient upstreamClient,
            String endpoint,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        this.mapper = mapper;
        this.stockMapper = stockMapper;
        this.upstreamClient = upstreamClient;
        this.endpoint = endpoint;
        this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public ToolResult announcements(String rawCode, Integer days) {
        // Python `int(arguments.get("days") or 180)`：缺省、null、0 都回到 180
        int window = days == null || days == 0 ? DEFAULT_DAYS : days;
        window = Math.min(Math.max(window, MIN_DAYS), MAX_DAYS);
        String code = EastmoneySupport.normalize(rawCode);
        if (code.isEmpty()) {
            return ToolResult.failure(rawCode, "请输入股票代码");
        }
        try {
            if (!knownExchange(code)) {
                return ToolResult.failure(code, code + "：无法判断交易所");
            }
            List<AnnouncementEntity> rows;
            List<AnnouncementEntity> cached = readRows(code);
            if (fresh(cached)) {
                rows = cached;
            } else {
                List<AnnouncementEntity> fetched;
                try {
                    fetched = fetch(code);
                } catch (RuntimeException error) {
                    if (cached.isEmpty()) {
                        return ToolResult.failure(code, error.getMessage());
                    }
                    return assemble(code, cached, window);
                }
                if (fetched.isEmpty()) {
                    // 上游正常但没有公告；有缓存时 Python 会退回旧缓存
                    if (!cached.isEmpty()) {
                        return assemble(code, cached, window);
                    }
                    return noAnnouncements(code, window);
                }
                save(code, fetched);
                rows = readRows(code);
            }
            return assemble(code, rows, window);
        } catch (RuntimeException error) {
            // 端口不抛异常：上游、读库或组装出的意外错误同样折成失败
            return ToolResult.failure(code, error.getMessage());
        }
    }

    private List<AnnouncementEntity> fetch(String code) {
        String cutoff = LocalDate.now(clock).minusDays(RETENTION_DAYS).toString();
        String fetchedAt = LocalDateTime.now(clock).format(TIMESTAMP);
        Map<String, AnnouncementEntity> rows = new LinkedHashMap<>();
        for (int page = 1; page <= MAX_PAGES; page++) {
            List<JsonNode> items = fetchPage(code, page);
            if (items.isEmpty()) {
                break;
            }
            String oldest = null;
            for (JsonNode raw : items) {
                String artCode = EastmoneySupport.text(raw.get("art_code"));
                String noticeDate = dateText(raw.get("notice_date"));
                String title = EastmoneySupport.text(raw.get("title_ch"));
                if (title == null) {
                    title = EastmoneySupport.text(raw.get("title"));
                }
                if (artCode == null || noticeDate == null || title == null) {
                    continue;
                }
                if (noticeDate.compareTo(cutoff) < 0) {
                    continue;
                }
                if (oldest == null || noticeDate.compareTo(oldest) < 0) {
                    oldest = noticeDate;
                }
                AnnouncementEntity row = new AnnouncementEntity();
                row.setCode(code);
                row.setArtCode(artCode);
                row.setNoticeDate(noticeDate);
                row.setTitle(stripPrefix(title, raw.path("codes")));
                row.setCategories(categories(raw.path("columns")));
                row.setUrl(DETAIL_URL.formatted(code, artCode));
                row.setFetchedAt(fetchedAt);
                rows.put(artCode, row);
            }
            // 翻页停止：本页不满、窗口内没有再早的条目，或已到最大页数
            if (items.size() < PAGE_SIZE || oldest == null || oldest.compareTo(cutoff) < 0) {
                break;
            }
        }
        return new ArrayList<>(rows.values());
    }

    /** 抓一页原始公告；空 body、HTML/WAF、坏 JSON、信封形状不对都折成异常。 */
    private List<JsonNode> fetchPage(String code, int page) {
        String url =
                endpoint
                        + "?sr=-1&page_size="
                        + PAGE_SIZE
                        + "&page_index="
                        + page
                        + "&ann_type=A&client_source=web&stock_list="
                        + code;
        JsonNode root;
        try {
            root = MAPPER.readTree(upstreamClient.get(url, Map.of()));
        } catch (JsonProcessingException error) {
            throw new AnnouncementException("获取公告失败：" + error.getMessage());
        } catch (RuntimeException error) {
            throw new AnnouncementException("获取公告失败：" + error.getMessage());
        }
        if (root == null || !root.isObject()) {
            throw new AnnouncementException("公告接口返回格式异常");
        }
        JsonNode data = root.path("data");
        JsonNode list = data.path("list");
        if (!data.isObject() || !list.isArray()) {
            throw new AnnouncementException("公告接口返回格式异常");
        }
        List<JsonNode> items = new ArrayList<>();
        for (JsonNode item : list) {
            if (item.isObject()) {
                items.add(item);
            }
        }
        return items;
    }

    private void save(String code, List<AnnouncementEntity> rows) {
        String cutoff = LocalDate.now(clock).minusDays(RETENTION_DAYS).toString();
        transactionTemplate.executeWithoutResult(
                status -> {
                    // Python `_save_rows`：先裁掉保留窗口外的行，再 insert or replace
                    mapper.delete(
                            Wrappers.<AnnouncementEntity>query()
                                    .eq("code", code)
                                    .lt("notice_date", cutoff));
                    for (AnnouncementEntity row : rows) {
                        mapper.upsert(row);
                    }
                });
    }

    private List<AnnouncementEntity> readRows(String code) {
        return mapper.selectList(
                Wrappers.<AnnouncementEntity>query()
                        .eq("code", code)
                        .orderByDesc("notice_date")
                        .orderByDesc("art_code"));
    }

    /** Python {@code _age_hours}：取缓存里最新的 fetched_at，空缓存一律视为过期。 */
    private boolean fresh(List<AnnouncementEntity> rows) {
        String newest = null;
        for (AnnouncementEntity row : rows) {
            if (row.getFetchedAt() != null
                    && (newest == null || row.getFetchedAt().compareTo(newest) > 0)) {
                newest = row.getFetchedAt();
            }
        }
        if (newest == null) {
            return false;
        }
        try {
            LocalDateTime fetched = LocalDateTime.parse(newest, TIMESTAMP);
            return Duration.between(fetched, LocalDateTime.now(clock))
                            .compareTo(Duration.ofHours(MAX_AGE_HOURS))
                    <= 0;
        } catch (DateTimeParseException error) {
            return false;
        }
    }

    /** 照抄 {@code assistant/tools.py:_announcements}。 */
    private ToolResult assemble(String code, List<AnnouncementEntity> rows, int days) {
        String cutoff = LocalDate.now(clock).minusDays(days).toString();
        List<AnnouncementEntity> window = new ArrayList<>();
        for (AnnouncementEntity row : rows) {
            if (row.getNoticeDate() == null
                    || row.getNoticeDate().isEmpty()
                    || row.getTitle() == null
                    || row.getTitle().isEmpty()
                    || row.getNoticeDate().compareTo(cutoff) < 0) {
                continue;
            }
            window.add(row);
        }
        window.sort(NEWEST_FIRST);
        if (window.isEmpty()) {
            return noAnnouncements(code, days);
        }

        String name = stockMapper.selectNameByCode(code);
        List<Map<String, Object>> items = new ArrayList<>();
        List<Source> sources = new ArrayList<>();
        for (AnnouncementEntity row :
                window.subList(0, Math.min(TOOL_ITEMS, window.size()))) {
            String title = cleanTitle(row.getTitle());
            Classified classified =
                    classify(title, row.getCategories() == null ? "" : row.getCategories());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("date", row.getNoticeDate());
            item.put("title", title);
            item.put("tags", classified.tags());
            item.put("level", classified.level());
            items.add(item);

            String artCode = row.getArtCode() == null ? "-" : row.getArtCode();
            sources.add(
                    new Source(
                            "announcement:" + code + ":" + row.getNoticeDate() + ":" + artCode,
                            SOURCE_LABEL,
                            clip(title, 60),
                            row.getNoticeDate(),
                            // 公告摘要没有带出抓取时间，引用里留空
                            "",
                            row.getUrl() == null ? "" : row.getUrl()));
        }

        String asOf = window.get(0).getNoticeDate();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("code", code);
        data.put("name", name);
        data.put("days", days);
        data.put("as_of", asOf);
        data.put("items", items);
        return ToolResult.ok(code, asOf, sources, data);
    }

    /** Python {@code _announcements}：窗口内没有公告是失败，不是成功空结果。 */
    private static ToolResult noAnnouncements(String code, int days) {
        return ToolResult.failure(code, code + "：近 " + days + " 天没有公告");
    }

    /** {@code stock/announcements.py:classify}：有利空标签时按"异常"算。 */
    private static Classified classify(String title, String categories) {
        String haystack = title + " " + categories;
        List<String> tags = new ArrayList<>();
        String level = null;
        for (String[] rule : WARNING_RULES) {
            if (containsAny(haystack, rule)) {
                tags.add(rule[0]);
                level = "warning";
            }
        }
        for (String[] rule : INFO_RULES) {
            if (containsAny(haystack, rule)) {
                tags.add(rule[0]);
                if (level == null) {
                    level = "info";
                }
            }
        }
        return new Classified(tags, level);
    }

    private static boolean containsAny(String haystack, String[] rule) {
        for (int i = 1; i < rule.length; i++) {
            if (haystack.contains(rule[i])) {
                return true;
            }
        }
        return false;
    }

    /** {@code _strip_prefix}：去掉标题开头重复的公司简称。 */
    private static String stripPrefix(String title, JsonNode codes) {
        if (!codes.isArray()) {
            return title;
        }
        for (JsonNode item : codes) {
            String shortName = EastmoneySupport.text(item.get("short_name"));
            if (shortName == null) {
                continue;
            }
            for (String separator : new String[] {":", "："}) {
                String prefix = shortName + separator;
                if (title.startsWith(prefix)) {
                    return title.substring(prefix.length()).strip();
                }
            }
        }
        return title;
    }

    /** {@code _clean_title}：去掉句首的"XX股份有限公司/有限责任公司"。 */
    private static String cleanTitle(String title) {
        String cleaned = COMPANY_PREFIX.matcher(title).replaceFirst("").strip();
        return cleaned.isEmpty() ? title : cleaned;
    }

    private static String categories(JsonNode columns) {
        List<String> parts = new ArrayList<>();
        if (columns.isArray()) {
            for (JsonNode column : columns) {
                JsonNode name = column.get("column_name");
                if (name != null && !name.isNull() && !name.asText().isEmpty()) {
                    parts.add(name.asText().strip());
                }
            }
        }
        return String.join("、", parts);
    }

    /** Python {@code _date_text}：只取前 10 个字符，不做日期校验。 */
    private static String dateText(JsonNode node) {
        String text = EastmoneySupport.text(node);
        return text == null ? null : text.substring(0, Math.min(10, text.length()));
    }

    /** {@code assistant/tools.py:_clip}：按字符数截断，不是 stock 里按显示宽度的同名函数。 */
    private static String clip(String text, int maxChars) {
        String value = text == null ? "" : text.strip();
        if (value.length() <= maxChars) {
            return value;
        }
        String head = value.substring(0, maxChars);
        int end = head.length();
        while (end > 0 && "，、；： ".indexOf(head.charAt(end - 1)) >= 0) {
            end--;
        }
        return head.substring(0, end) + "…";
    }

    /** {@code su.get_stock_prefix} 的纯数字分支。 */
    private static boolean knownExchange(String code) {
        return code.startsWith("600")
                || code.startsWith("601")
                || code.startsWith("603")
                || code.startsWith("605")
                || code.startsWith("688")
                || code.startsWith("000")
                || code.startsWith("001")
                || code.startsWith("002")
                || code.startsWith("003")
                || code.startsWith("300")
                || code.startsWith("301")
                || code.startsWith("920")
                || code.startsWith("4")
                || code.startsWith("8");
    }

    private record Classified(List<String> tags, String level) {}

    /** 解析失败也要带中文原因走到 ToolResult.failure，不能逃出端口。 */
    private static final class AnnouncementException extends RuntimeException {
        AnnouncementException(String message) {
            super(message);
        }
    }
}
