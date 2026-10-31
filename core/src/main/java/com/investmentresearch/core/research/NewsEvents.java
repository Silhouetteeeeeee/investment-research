package com.investmentresearch.core.research;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Port of {@code research/news_events.py} 的纯函数部分：把各来源的原始快讯整理成可复核的事件。
 *
 * <p>行业映射是关键词候选，不是因果结论；作用是把当天几百条快讯收敛到十几条值得继续查的事件。
 */
public final class NewsEvents {
    public static final int NEWS_LIMIT = 12;
    public static final int MAX_SUMMARY = 260;

    static final String POLICY = "政策";
    static final String INDUSTRY = "产业";
    static final String COMPANY = "公司";
    static final String OVERSEAS = "海外";

    static final List<String> POLICY_WORDS =
            List.of(
                    "国务院", "央行", "人民银行", "证监会", "发改委", "财政部", "工信部", "商务部", "住建部", "市场监管",
                    "统计局", "税务总局", "政策", "通知", "意见", "规划", "降准", "降息", "关税", "补贴", "监管",
                    "试点", "条例", "办法", "规定", "税收", "医保", "集采", "专项债", "特别国债");
    static final List<String> COMPANY_WORDS =
            List.of(
                    "公告", "业绩", "预增", "预亏", "回购", "减持", "增持", "并购", "重组", "中标", "合同", "分红",
                    "停牌", "复牌", "退市", "立案", "处罚", "问询", "解禁", "定增", "配股", "股东", "实控人", "董事长");
    static final List<String> MAJOR_COMPANY_WORDS =
            List.of(
                    "业绩预告", "业绩快报", "预增", "预亏", "回购", "减持", "增持", "并购", "重组", "中标", "合同", "分红",
                    "退市", "立案", "处罚", "定增", "实控人");
    static final List<String> OVERSEAS_STRONG_WORDS =
            List.of(
                    "美股", "美联储", "纳指", "纳斯达克", "道指", "标普", "欧股", "欧洲央行", "日本央行", "港股", "恒生",
                    "美债", "非农", "CPI", "PCE", "加息", "降息预期", "英国", "加拿大", "日本", "欧洲", "韩国", "美国");
    static final List<String> OVERSEAS_MACRO_WORDS = List.of("原油", "黄金", "美元", "汇率");
    static final List<String> LOW_CONFIDENCE_WORDS =
            List.of("传闻", "网传", "据悉", "未经证实", "市场消息", "知情人士", "或将");
    static final List<String> OFFICIAL_WORDS =
            List.of("国务院", "央行", "人民银行", "证监会", "发改委", "财政部", "工信部", "商务部", "住建部", "统计局", "税务总局");
    // 这些词说明是营销/观点/行情播报，不是当天必须处理的事件
    static final List<String> NOISE_WORDS =
            List.of(
                    "ETF", "基金", "净值", "涨超", "飘红", "标的指数", "机构看好", "研报", "晨会", "首席", "观点", "展望",
                    "认为", "表示", "解读", "专访", "对话", "午盘", "收盘播报", "开盘播报", "盘中快照");
    static final List<String> HARD_NOISE_WORDS =
            List.of("ETF", "净值", "涨超", "飘红", "标的指数", "午盘", "收盘播报", "开盘播报", "盘中快照");
    static final List<String> TITLE_LABELS = List.of("电报解读", "快讯", "要闻", "焦点", "独家", "重磅", "公告");

    /** 行业名尽量对齐同花顺行业板块，方便和轮动数据互相印证。 */
    static final Map<String, List<String>> INDUSTRY_RULES = industryRules();

    static final Map<String, String> INDUSTRY_PATHS = industryPaths();

    static final Map<String, Integer> CATEGORY_LIMITS =
            Map.of(POLICY, 6, INDUSTRY, 4, COMPANY, 3, OVERSEAS, 3);
    static final int POLICY_NO_INDUSTRY_LIMIT = 2;

    private static final Pattern ASCII_TOKEN = Pattern.compile("^[A-Za-z0-9]+$");
    private static final Map<String, Pattern> WORD_BOUNDARY = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Pattern NON_WORD = Pattern.compile("[^0-9A-Za-z\\x{4e00}-\\x{9fff}]");
    private static final Pattern BRACKET_LABEL = Pattern.compile("^[【\\[]?\\s*([^】\\[]{1,12})[】\\]]\\s*");
    private static final Pattern INNER_BRACKET = Pattern.compile("【(.+?)】");
    private static final Pattern SENTENCE_END = Pattern.compile("[。！？\\n]");
    private static final Pattern CLOCK = Pattern.compile("(\\d{1,2}:\\d{2}(?::\\d{2})?)");
    private static final Pattern LEADING_BRACKET = Pattern.compile("^【.+?】");
    private static final Pattern CLS_PREFIX = Pattern.compile("^财联社.*?电，");

    private NewsEvents() {}

    /** 单条快讯 → 事件；栏目名噪声、无法归类、产业类却没有行业命中的都返回 {@code null}。 */
    public static Map<String, Object> makeEvent(
            Map<String, Object> record, String sourceName, String sourceId, String today, String kind) {
        Map<String, Object> normalized = normalize(record, kind);
        if (normalized == null || isNoise((String) normalized.get("title"))) {
            return null;
        }
        String title = (String) normalized.get("title");
        String summary = (String) normalized.get("summary");
        String text = title + " " + summary;
        List<Map<String, Object>> industries = industries(title, summary);
        String category = category(title, text, industries);
        if (category == null) {
            return null;
        }
        if (INDUSTRY.equals(category) && industries.isEmpty()) {
            return null;
        }

        Map<String, Object> source = new LinkedHashMap<>();
        source.put("name", sourceName);
        source.put("url", normalized.get("url"));
        source.put("published_at", normalized.get("published_at"));
        source.put("source_id", sourceId);

        String confidence = confidence(List.of(sourceName), text);
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("title", title);
        event.put("summary", summary);
        event.put("category", category);
        event.put("source", sourceName);
        event.put("source_id", sourceId);
        event.put("published_at", normalized.get("published_at"));
        event.put("url", normalized.get("url"));
        event.put("sources", new ArrayList<>(List.of(source)));
        event.put("industries", industries);
        event.put(
                "keywords",
                head(
                        matches(
                                text,
                                concat(
                                        POLICY_WORDS, COMPANY_WORDS, OVERSEAS_STRONG_WORDS, OVERSEAS_MACRO_WORDS)),
                        6));
        event.put("confidence", confidence);
        event.put("uncertainty", uncertainty(category, industries, confidence));
        event.put("impact_path", impactPath(category, industries));
        event.put("score", score(event, today));
        return event;
    }

    /** 同一事件被多家媒体报道时合并来源，而不是重复列出。 */
    public static List<Map<String, Object>> dedupeEvents(List<Map<String, Object>> events) {
        Map<String, Map<String, Object>> merged = new LinkedHashMap<>();
        for (Map<String, Object> event : events) {
            String key = dedupeKey(text(event.get("title")));
            if (key.isEmpty()) {
                continue;
            }
            Map<String, Object> existing = merged.get(key);
            if (existing == null) {
                merged.put(key, new LinkedHashMap<>(event));
                continue;
            }
            existing.put(
                    "sources",
                    mergeSources(listOf(existing.get("sources")), listOf(event.get("sources"))));
            existing.put(
                    "industries",
                    mergeIndustries(listOf(existing.get("industries")), listOf(event.get("industries"))));
            List<Object> keywords = new ArrayList<>(distinct(listOf(existing.get("keywords"))));
            keywords.addAll(listOf(event.get("keywords")));
            existing.put("keywords", head(distinct(keywords), 6));
            existing.put(
                    "published_at",
                    maxStr(text(existing.get("published_at")), text(event.get("published_at"))));
            String left = text(existing.get("summary"));
            String right = text(event.get("summary"));
            existing.put("summary", right.length() > left.length() ? right : left);
            List<String> names = new ArrayList<>();
            for (Map<String, Object> item : listOf(existing.get("sources"))) {
                String name = text(item.get("name"));
                if (!name.isEmpty()) {
                    names.add(name);
                }
            }
            String body = text(existing.get("title")) + " " + text(existing.get("summary"));
            String confidence = confidence(names, body);
            existing.put("confidence", confidence);
            existing.put(
                    "uncertainty",
                    uncertainty(
                            text(existing.get("category")),
                            listOf(existing.get("industries")),
                            confidence));
            existing.put(
                    "impact_path",
                    impactPath(text(existing.get("category")), listOf(existing.get("industries"))));
            double top = Math.max(number(existing.get("score")), number(event.get("score")));
            existing.put(
                    "score",
                    round(top + 0.3 * (listOf(existing.get("sources")).size() - 1), 2));
        }
        List<Map<String, Object>> result = new ArrayList<>(merged.values());
        result.sort(
                Comparator.comparingDouble((Map<String, Object> item) -> number(item.get("score")))
                        .thenComparing(item -> text(item.get("published_at")))
                        .reversed());
        return result;
    }

    /** 按类别配额挑选，避免政策或公司新闻把行业与海外事件全部挤掉。 */
    public static List<Map<String, Object>> selectEvents(List<Map<String, Object>> events, int limit) {
        List<Map<String, Object>> selected = new ArrayList<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        int policyWithoutIndustry = 0;
        for (Map<String, Object> event : events) {
            if (selected.size() >= limit) {
                break;
            }
            String category = text(event.get("category"));
            if (counts.getOrDefault(category, 0)
                    >= CATEGORY_LIMITS.getOrDefault(category, limit)) {
                continue;
            }
            if (POLICY.equals(category) && listOf(event.get("industries")).isEmpty()) {
                if (policyWithoutIndustry >= POLICY_NO_INDUSTRY_LIMIT) {
                    continue;
                }
                policyWithoutIndustry++;
            }
            selected.add(event);
            counts.merge(category, 1, Integer::sum);
        }
        return selected;
    }

    // ------------------------------------------------------------------ 归一化

    private static Map<String, Object> normalize(Map<String, Object> record, String kind) {
        String title = titleFrom(record);
        if (title.isEmpty()) {
            return null;
        }
        String dateValue;
        String timeValue;
        switch (kind) {
            case "cls" -> {
                dateValue = text(record.get("发布日期"));
                timeValue = text(record.get("发布时间"));
            }
            case "cctv" -> {
                dateValue = text(record.get("date"));
                timeValue = "";
            }
            default -> {
                dateValue = text(record.get("发布时间"));
                timeValue = "";
            }
        }
        String url = text(record.get("链接")).strip();
        if (url.isEmpty()) {
            url = text(record.get("url")).strip();
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("title", title);
        normalized.put("summary", summaryFrom(record));
        normalized.put("published_at", publishedAt(dateValue, timeValue.isEmpty() ? null : timeValue));
        normalized.put("url", url.isEmpty() ? null : url);
        return normalized;
    }

    private static String titleFrom(Map<String, Object> record) {
        String title = text(record.get("标题"));
        if (title.isEmpty()) {
            title = text(record.get("title"));
        }
        title = stripLabel(title.strip());
        if (!title.isEmpty()) {
            return cut(title, 100);
        }
        String content = text(record.get("内容"));
        if (content.isEmpty()) {
            content = text(record.get("content"));
        }
        if (content.isEmpty()) {
            content = text(record.get("摘要"));
        }
        content = content.strip();
        Matcher inner = INNER_BRACKET.matcher(content);
        if (inner.find()) {
            String label = inner.group(1).strip();
            if (!TITLE_LABELS.contains(label)) {
                return cut(label, 100);
            }
            content = content.substring(inner.end()).strip();
        }
        Matcher end = SENTENCE_END.matcher(content);
        String first = (end.find() ? content.substring(0, end.start()) : content).strip();
        return cut(first, 100);
    }

    private static String summaryFrom(Map<String, Object> record) {
        String text = text(record.get("摘要"));
        if (text.isEmpty()) {
            text = text(record.get("内容"));
        }
        if (text.isEmpty()) {
            text = text(record.get("content"));
        }
        text = LEADING_BRACKET.matcher(text.strip()).replaceFirst("").strip();
        text = CLS_PREFIX.matcher(text).replaceFirst("").strip();
        return cut(text, MAX_SUMMARY);
    }

    /** 快讯平台常把栏目名塞进标题，留着会让读者以为是事件本身。 */
    private static String stripLabel(String title) {
        Matcher match = BRACKET_LABEL.matcher(title);
        if (match.find() && TITLE_LABELS.contains(match.group(1).strip())) {
            title = title.substring(match.end());
        }
        title = title.replaceAll("^】+", "");
        return title.replaceAll("^[【】 ]+", "").replaceAll("[【】 ]+$", "");
    }

    private static String normDate(String value) {
        String digits = value.strip().replaceAll("\\D", "");
        if (digits.length() >= 8) {
            return digits.substring(0, 4) + "-" + digits.substring(4, 6) + "-" + digits.substring(6, 8);
        }
        return cut(value.strip(), 10);
    }

    private static String normTime(String value) {
        Matcher match = CLOCK.matcher(value.strip());
        return match.find() ? match.group(1) : value.strip();
    }

    private static String publishedAt(String dateValue, String timeValue) {
        String day = normDate(dateValue);
        if (timeValue != null) {
            String clock = normTime(timeValue);
            return clock.isEmpty() ? day : (day + " " + clock).strip();
        }
        String text = dateValue.strip();
        if (text.contains(" ") && CLOCK.matcher(text).find()) {
            return day + " " + normTime(text);
        }
        return day;
    }

    // ------------------------------------------------------------------ 归类

    /** 英文/数字关键词按词边界匹配，避免 EPC 撞上 PC、MAIN 撞上 AI。 */
    static boolean contains(String text, String word) {
        if (ASCII_TOKEN.matcher(word).matches()) {
            return WORD_BOUNDARY
                    .computeIfAbsent(word, key -> Pattern.compile("(?<![A-Za-z0-9])" + Pattern.quote(key) + "(?![A-Za-z0-9])"))
                    .matcher(text)
                    .find();
        }
        return text.contains(word);
    }

    static List<String> matches(String text, List<String> words) {
        List<String> hits = new ArrayList<>();
        for (String word : words) {
            if (contains(text, word)) {
                hits.add(word);
            }
        }
        return hits;
    }

    /** 标题命中权重高于正文：正文里一处泛词不足以认定行业相关。 */
    static List<Map<String, Object>> industries(String title, String summary) {
        List<Scored> hits = new ArrayList<>();
        int order = 0;
        for (Map.Entry<String, List<String>> rule : INDUSTRY_RULES.entrySet()) {
            List<String> inTitle = matches(title, rule.getValue());
            List<String> inSummary = new ArrayList<>();
            for (String word : rule.getValue()) {
                if (!inTitle.contains(word) && contains(summary, word)) {
                    inSummary.add(word);
                }
            }
            int score = 3 * inTitle.size() + inSummary.size();
            if (!inTitle.isEmpty() || score >= 2) {
                List<String> matched = new ArrayList<>(inTitle);
                matched.addAll(inSummary);
                hits.add(new Scored(score, order, rule.getKey(), matched));
            }
            order++;
        }
        // 同分时按规则表的顺序（大致按行业权重）而不是名字拼音
        hits.sort(Comparator.comparingInt(Scored::score).reversed().thenComparingInt(Scored::order));
        Set<String> seen = new LinkedHashSet<>();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Scored hit : hits) {
            if (!seen.add(hit.name())) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", hit.name());
            item.put("keywords", head(hit.matched(), 5));
            result.add(item);
            if (result.size() >= 3) {
                break;
            }
        }
        return result;
    }

    /**
     * 先看标题定性质，标题不够明确时再借助正文；避免把公司新闻误判成政策。
     *
     * <p>海外新闻以标题里的市场/国家词为主，正文里的“美元/原油”只作为补充。
     */
    static String category(String title, String text, List<Map<String, Object>> industries) {
        if (!matches(title, OVERSEAS_STRONG_WORDS).isEmpty()) {
            return OVERSEAS;
        }
        List<String> policyTitle = matches(title, POLICY_WORDS);
        List<String> companyTitle = matches(title, COMPANY_WORDS);
        if (official(title) || (!policyTitle.isEmpty() && companyTitle.isEmpty())) {
            return POLICY;
        }
        if (!companyTitle.isEmpty() && !matches(title, MAJOR_COMPANY_WORDS).isEmpty()) {
            return COMPANY;
        }
        if (!industries.isEmpty()) {
            return INDUSTRY;
        }
        if (!matches(text, OVERSEAS_STRONG_WORDS).isEmpty()) {
            return OVERSEAS;
        }
        if (!matches(text, POLICY_WORDS).isEmpty()) {
            return POLICY;
        }
        if (!matches(text, OVERSEAS_MACRO_WORDS).isEmpty() && companyTitle.isEmpty()) {
            return OVERSEAS;
        }
        return null;
    }

    static String impactPath(String category, List<Map<String, Object>> industries) {
        if (industries.isEmpty()) {
            return "事件 → 市场风险偏好 → 指数与相关板块（行业映射不明确）";
        }
        String path = INDUSTRY_PATHS.getOrDefault(text(industries.get(0).get("name")), "该行业供需、价格与盈利预期");
        return switch (category) {
            case POLICY -> "政策 → " + path;
            case OVERSEAS -> "海外市场 → 风险偏好/汇率/商品价格 → " + path;
            case COMPANY -> "公司事件 → " + path + "（先看直接影响，再看同业比较）";
            default -> "产业变化 → " + path;
        };
    }

    static boolean official(String text) {
        for (String word : OFFICIAL_WORDS) {
            if (text.contains(word)) {
                return true;
            }
        }
        return false;
    }

    static String confidence(List<String> sourceNames, String text) {
        boolean low = false;
        for (String word : LOW_CONFIDENCE_WORDS) {
            if (text.contains(word)) {
                low = true;
                break;
            }
        }
        if (low && sourceNames.size() < 2) {
            return "低";
        }
        if (sourceNames.contains("新闻联播") || official(text) || sourceNames.size() >= 2) {
            return "高";
        }
        return "中";
    }

    static String uncertainty(String category, List<Map<String, Object>> industries, String confidence) {
        String text =
                switch (category) {
                    case POLICY -> "政策细则、执行力度与落地时间未定";
                    case OVERSEAS -> "隔夜或外部市场变化，A 股映射不一定同步";
                    case COMPANY -> "公告细节、影响金额与会计处理待核";
                    default -> "单条新闻不足以确认行业趋势，需后续订单、价格与资金数据验证";
                };
        if (industries.isEmpty()) {
            text += "；行业映射不明确，可能只影响风险偏好";
        }
        if ("低".equals(confidence)) {
            text = "消息尚未获得官方确认；" + text;
        }
        return text;
    }

    static double score(Map<String, Object> event, String today) {
        Map<String, Double> weights = Map.of(POLICY, 3.0, OVERSEAS, 2.5, INDUSTRY, 2.0, COMPANY, 1.0);
        double score = weights.getOrDefault(text(event.get("category")), 0.0);
        score += Math.min(listOf(event.get("industries")).size(), 3) * 0.6;
        String text = text(event.get("title")) + " " + text(event.get("summary"));
        if (official(text)) {
            score += 0.8;
        }
        if (text(event.get("published_at")).startsWith(today)) {
            score += 0.5;
        }
        score += Math.min(listOf(event.get("keywords")).size(), 4) * 0.1;
        if ("高".equals(text(event.get("confidence")))) {
            score += 0.4;
        }
        String title = text(event.get("title"));
        for (String word : NOISE_WORDS) {
            if (title.contains(word)) {
                score -= 0.8;
                break;
            }
        }
        return round(score, 2);
    }

    /** ETF 行情、基金净值、盘中播报不是当天要处理的“事件”。 */
    static boolean isNoise(String title) {
        if (official(title)) {
            return false;
        }
        for (String word : HARD_NOISE_WORDS) {
            if (title.contains(word)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ 合并

    static String dedupeKey(String title) {
        return cut(NON_WORD.matcher(title).replaceAll(""), 32);
    }

    private static List<Map<String, Object>> mergeSources(List<Map<String, Object>> first, List<Map<String, Object>> second) {
        List<Map<String, Object>> merged = new ArrayList<>();
        Set<List<String>> seen = new LinkedHashSet<>();
        List<Map<String, Object>> all = new ArrayList<>(first);
        all.addAll(second);
        for (Map<String, Object> item : all) {
            List<String> key = java.util.Arrays.asList(text(item.get("name")), text(item.get("url")), text(item.get("published_at")));
            if (seen.add(key)) {
                merged.add(item);
            }
        }
        return merged;
    }

    private static List<Map<String, Object>> mergeIndustries(List<Map<String, Object>> first, List<Map<String, Object>> second) {
        Map<String, Map<String, Object>> byName = new LinkedHashMap<>();
        for (Map<String, Object> item : first) {
            byName.put(text(item.get("name")), item);
        }
        for (Map<String, Object> item : second) {
            byName.putIfAbsent(text(item.get("name")), item);
        }
        return new ArrayList<>(byName.values()).subList(0, Math.min(3, byName.size()));
    }

    // ------------------------------------------------------------------ 小工具

    /** {@code round()} 与 Python 一致：对 double 的精确值做银行家舍入。 */
    static double round(double value, int digits) {
        return new BigDecimal(value).setScale(digits, RoundingMode.HALF_EVEN).doubleValue();
    }

    static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    static double number(Object value) {
        if (value instanceof Number num) {
            return num.doubleValue();
        }
        try {
            return Double.parseDouble(text(value));
        } catch (NumberFormatException error) {
            return 0.0;
        }
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> listOf(Object value) {
        return value == null ? List.of() : (List<Map<String, Object>>) value;
    }

    private static List<Object> distinct(List<?> values) {
        return new ArrayList<>(new LinkedHashSet<>(values));
    }

    private static <T> List<T> head(List<T> values, int size) {
        return new ArrayList<>(values.subList(0, Math.min(size, values.size())));
    }

    private static String cut(String value, int size) {
        return value.length() <= size ? value : value.substring(0, size);
    }

    private static String maxStr(String left, String right) {
        return left.compareTo(right) >= 0 ? left : right;
    }

    @SafeVarargs
    private static List<String> concat(List<String>... groups) {
        List<String> all = new ArrayList<>();
        for (List<String> group : groups) {
            all.addAll(group);
        }
        return all;
    }

    private record Scored(int score, int order, String name, List<String> matched) {}

    private static Map<String, List<String>> industryRules() {
        Map<String, List<String>> rules = new LinkedHashMap<>();
        rules.put("半导体", List.of("半导体", "芯片", "集成电路", "晶圆", "光刻", "封测", "存储芯片"));
        rules.put("光伏设备", List.of("光伏", "硅料", "硅片", "组件", "逆变器", "太阳能", "钙钛矿"));
        rules.put("风电设备", List.of("风电", "海上风电", "风机"));
        rules.put("电池", List.of("锂电", "电池", "储能", "充电桩", "固态电池"));
        rules.put(
                "汽车整车",
                List.of("新能源车", "整车", "整车厂", "车企", "车型", "汽车", "智能驾驶", "自动驾驶"));
        rules.put("汽车零部件", List.of("汽车零部件", "零部件", "轮胎", "座椅"));
        rules.put("化学制药", List.of("创新药", "化学制药", "仿制药", "医药", "医保", "集采"));
        rules.put("生物制品", List.of("疫苗", "生物制品", "血制品", "生物医药"));
        rules.put("医疗器械", List.of("医疗器械", "医疗设备", "影像设备"));
        rules.put("中药", List.of("中药", "中医药"));
        rules.put("医疗服务", List.of("医疗服务", "医院", "CXO", "临床"));
        rules.put("白酒", List.of("白酒", "酒企", "茅台", "五粮液"));
        rules.put("食品加工制造", List.of("食品", "零食", "调味品", "预制菜"));
        rules.put("饮料制造", List.of("饮料", "乳业", "啤酒"));
        rules.put("房地产", List.of("房地产", "地产", "楼市", "房贷", "保障房", "城中村"));
        rules.put("银行", List.of("银行", "信贷", "社融", "存款", "贷款"));
        rules.put(
                "证券",
                List.of("券商", "证券", "资本市场", "IPO", "注册制", "并购重组", "债市", "债券", "国债", "交易所", "非银"));
        rules.put("保险", List.of("保险", "险企", "保费"));
        rules.put("军工装备", List.of("军工", "国防", "航空", "航天", "导弹", "军贸"));
        rules.put("军工电子", List.of("军工电子", "雷达", "军工芯片"));
        rules.put("软件开发", List.of("人工智能", "AI", "大模型", "信创", "软件", "算力"));
        rules.put("IT服务", List.of("数据中心", "云计算", "算力租赁", "服务器"));
        rules.put("计算机设备", List.of("计算机设备", "服务器", "PC"));
        rules.put("通信设备", List.of("5G", "6G", "通信设备", "光模块", "交换机"));
        rules.put("自动化设备", List.of("机器人", "人形机器人", "减速器", "伺服", "工控"));
        rules.put("专用设备", List.of("专用设备", "锂电设备", "光伏设备", "半导体设备"));
        rules.put("工程机械", List.of("工程机械", "挖掘机", "起重机"));
        rules.put("贵金属", List.of("黄金", "贵金属", "金价"));
        rules.put("油气开采及服务", List.of("原油", "石油", "天然气", "油气", "页岩气"));
        rules.put("石油加工贸易", List.of("炼化", "成品油", "石油加工"));
        rules.put("煤炭开采加工", List.of("煤炭", "动力煤", "焦煤"));
        rules.put("电力", List.of("电力", "火电", "水电", "核电", "电价"));
        rules.put("工业金属", List.of("铜", "铝", "钢铁", "工业金属"));
        rules.put("小金属", List.of("稀土", "钨", "钼", "小金属"));
        rules.put("能源金属", List.of("锂矿", "钴", "镍", "能源金属"));
        rules.put("种植业与林业", List.of("种业", "粮食", "农业", "转基因"));
        rules.put("养殖业", List.of("养猪", "生猪", "养殖", "禽"));
        rules.put("旅游及酒店", List.of("旅游", "酒店", "景区", "免签"));
        // “免税店”写全，避免把“免税进口棉花”这类税收政策误判成零售
        rules.put("零售", List.of("免税店", "零售", "百货", "超市"));
        rules.put("互联网电商", List.of("电商", "直播带货", "跨境电商"));
        rules.put("游戏", List.of("游戏", "版号", "手游"));
        rules.put("文化传媒", List.of("传媒", "广告", "出版", "短剧"));
        rules.put("影视院线", List.of("影视", "电影", "院线"));
        rules.put("物流", List.of("物流", "快递", "仓储"));
        rules.put("港口航运", List.of("港口", "航运", "集运", "运价"));
        rules.put("化学制品", List.of("化工", "化学品", "农药", "化肥"));
        rules.put("建筑材料", List.of("建材", "水泥", "玻璃", "防水"));
        rules.put("建筑装饰", List.of("基建", "建筑", "工程", "装饰"));
        rules.put("白色家电", List.of("白电", "空调", "冰箱", "洗衣机"));
        rules.put("黑色家电", List.of("黑电", "电视", "面板"));
        rules.put("消费电子", List.of("消费电子", "苹果", "华为", "手机", "MR"));
        rules.put("环境治理", List.of("环保", "污水", "固废", "碳中和"));
        rules.put("教育", List.of("教育", "职业教育", "培训"));
        rules.put("美容护理", List.of("化妆品", "医美", "美容"));
        rules.put("纺织制造", List.of("纺织", "棉", "服装代工"));
        rules.put("服装家纺", List.of("服装", "家纺", "品牌服饰"));
        rules.put("造纸", List.of("造纸", "纸浆", "纸价"));
        return java.util.Collections.unmodifiableMap(rules);
    }

    private static Map<String, String> industryPaths() {
        Map<String, String> paths = new LinkedHashMap<>();
        paths.put("半导体", "半导体/芯片需求与国产替代预期 → 设计、制造、设备、材料");
        paths.put("光伏设备", "装机需求与产业链价格 → 硅料、组件、逆变器盈利");
        paths.put("风电设备", "招标与装机节奏 → 风机、海缆、塔筒订单");
        paths.put("电池", "新能源车与储能需求 → 电池排产、材料价格");
        paths.put("汽车整车", "销量与价格战 → 整车盈利与市场份额");
        paths.put("汽车零部件", "整车排产与客户结构 → 零部件订单与毛利率");
        paths.put("化学制药", "医保支付与集采价格 → 药品放量与利润率");
        paths.put("生物制品", "批签发与研发进展 → 疫苗、血制品销售");
        paths.put("医疗器械", "医院采购与集采 → 设备与耗材放量");
        paths.put("中药", "基药与医保政策 → 中药处方与品牌溢价");
        paths.put("医疗服务", "就诊需求与支付能力 → 医院、CXO 订单");
        paths.put("白酒", "消费场景与渠道库存 → 批价、回款与利润率");
        paths.put("食品加工制造", "成本与终端需求 → 毛利率与渠道库存");
        paths.put("饮料制造", "动销与渠道扩张 → 收入增速与费用率");
        paths.put("房地产", "销售与融资政策 → 拿地、开工、竣工链");
        paths.put("银行", "利率与信贷投放 → 净息差、资产质量");
        paths.put("证券", "市场成交与政策 → 经纪、投行、自营收入");
        paths.put("保险", "利率与保费增长 → 投资端与负债端");
        paths.put("军工装备", "订单与国防预算 → 主机厂、配套企业排产");
        paths.put("军工电子", "装备信息化 → 雷达、电子对抗、军工芯片");
        paths.put("软件开发", "AI 与信创投入 → 软件订单、云资源消耗");
        paths.put("IT服务", "算力需求 → 数据中心、服务器、租赁价格");
        paths.put("计算机设备", "算力与政企采购 → 服务器、PC 出货");
        paths.put("通信设备", "运营商与海外资本开支 → 光模块、交换机");
        paths.put("自动化设备", "制造业投资与机器人量产 → 减速器、伺服、本体");
        paths.put("专用设备", "下游扩产 → 设备订单与验收节奏");
        paths.put("工程机械", "基建与地产开工 → 挖机销量、出口");
        paths.put("贵金属", "实际利率与避险需求 → 金价、矿业利润");
        paths.put("油气开采及服务", "油价与资本开支 → 开采、油服订单");
        paths.put("石油加工贸易", "原油成本与成品油价差 → 炼化利润");
        paths.put("煤炭开采加工", "煤价与电煤需求 → 吨煤利润");
        paths.put("电力", "电价与来水/煤价 → 火电、水电、核电盈利");
        paths.put("工业金属", "地产与制造业需求 → 铜铝价格、加工费");
        paths.put("小金属", "供给约束与新能源需求 → 稀土、钨钼价格");
        paths.put("能源金属", "锂钴镍价格 → 矿端与冶炼利润");
        paths.put("种植业与林业", "粮价与政策 → 种业、种植收益");
        paths.put("养殖业", "猪价与存栏 → 养殖利润、饲料需求");
        paths.put("旅游及酒店", "出行人次与客单价 → 景区、酒店收入");
        paths.put("零售", "消费政策与客流 → 免税、百货销售");
        paths.put("互联网电商", "流量与履约成本 → 平台 GMV、货币化率");
        paths.put("游戏", "版号与流水 → 游戏公司收入、买量成本");
        paths.put("文化传媒", "广告需求与内容供给 → 传媒公司收入");
        paths.put("影视院线", "票房与排片 → 院线、制片方分账");
        paths.put("物流", "快递量与原燃料成本 → 单票收入、利润率");
        paths.put("港口航运", "运价与吞吐量 → 集运、干散货盈利");
        paths.put("化学制品", "原料价格与下游需求 → 价差、开工率");
        paths.put("建筑材料", "地产与基建开工 → 水泥、玻璃价格");
        paths.put("建筑装饰", "基建投资与回款 → 新签订单、现金流");
        paths.put("白色家电", "地产竣工与以旧换新 → 空调、冰洗出货");
        paths.put("黑色家电", "面板价格与需求 → 电视整机盈利");
        paths.put("消费电子", "新机周期与客户份额 → 零部件、组装订单");
        paths.put("环境治理", "环保政策与项目回款 → 运营、设备订单");
        paths.put("教育", "招生与政策 → 职业教育、培训收入");
        paths.put("美容护理", "消费力与新品 → 化妆品、医美渗透率");
        paths.put("纺织制造", "订单转移与棉价 → 代工毛利率");
        paths.put("服装家纺", "零售流水与库存 → 品牌服饰折扣率");
        paths.put("造纸", "纸浆价格与需求 → 纸企价差");
        return java.util.Collections.unmodifiableMap(paths);
    }
}
