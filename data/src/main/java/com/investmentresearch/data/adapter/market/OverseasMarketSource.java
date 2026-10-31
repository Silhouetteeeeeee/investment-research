package com.investmentresearch.data.adapter.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.investmentresearch.data.upstream.UpstreamClient;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 海外市场收盘（{@code research.news_events._overseas_markets}）。
 *
 * <p>美股用腾讯日线、港股用新浪快照——Python 走的是新浪两个接口，其中一个要跑 JS 解密。
 */
final class OverseasMarketSource {
    private static final String US_URL =
            "https://web.ifzq.gtimg.cn/appstock/app/usfqkline/get?param=%s,day,,,10,qfq";
    private static final String HK_URL = "https://hq.sinajs.cn/list=rt_hkHSI,rt_hkHSTECH";
    private static final Charset GB18030 = Charset.forName("GB18030");
    private static final Pattern HK_ROW = Pattern.compile("hq_str_rt_hk([A-Z]+)=\"([^\"]*)\"");
    /** 代码, 腾讯代码, 名称；错误键沿用 Python 的符号名。 */
    private static final List<String[]> US = List.of(
            new String[] {".INX", "usINX", "标普500"},
            new String[] {".DJI", "usDJI", "道琼斯工业指数"},
            new String[] {".IXIC", "usIXIC", "纳斯达克综合指数"});

    private final UpstreamClient client;

    OverseasMarketSource(UpstreamClient client) {
        this.client = client;
    }

    Overseas fetch(String today) {
        List<Map<String, Object>> items = new ArrayList<>();
        Map<String, String> errors = new LinkedHashMap<>();

        for (String[] item : US) {
            String symbol = item[0];
            try {
                List<Map<String, Object>> bars = usBars(item[1]);
                if (bars.size() < 2) {
                    errors.put(symbol, "历史数据不足");
                    continue;
                }
                Map<String, Object> last = bars.get(bars.size() - 1);
                Map<String, Object> previous = bars.get(bars.size() - 2);
                Double close = MarketSupport.number(last.get("close"));
                Double previousClose = MarketSupport.number(previous.get("close"));
                if (close == null || previousClose == null || previousClose == 0) {
                    errors.put(symbol, "收盘价缺失");
                    continue;
                }
                items.add(
                        MarketSupport.row(
                                "name", item[2],
                                "code", symbol,
                                "market", "美股",
                                "date", last.get("date"),
                                "close", MarketSupport.round(close, 2),
                                "chg_pct", MarketSupport.round((close / previousClose - 1) * 100, 2)));
            } catch (RuntimeException error) {
                errors.put(symbol, message(error));
            }
        }

        try {
            Map<String, String> rows = hkRows();
            String[][] wanted = {{"HSI", "恒生指数"}, {"HSTECH", "恒生科技指数"}};
            for (String[] item : wanted) {
                String[] fields = split(rows.get(item[0]));
                if (fields == null || fields.length < 9) {
                    continue;
                }
                Double price = MarketSupport.number(fields[6]);
                if (price == null) {
                    continue;
                }
                items.add(
                        MarketSupport.row(
                                "name", item[1],
                                "code", item[0],
                                "market", "港股",
                                "date", today,
                                "close", MarketSupport.round(price, 2),
                                "chg_pct", MarketSupport.round(MarketSupport.number(fields[8]), 2)));
            }
        } catch (RuntimeException error) {
            errors.put("HK", message(error));
        }
        return new Overseas(items, errors);
    }

    /** 每根是 {@code [日期, 开, 收, 高, 低, 量]}。 */
    private List<Map<String, Object>> usBars(String code) {
        String body = client.get(String.format(US_URL, code), Map.of("User-Agent", MarketSupport.USER_AGENT));
        JsonNode node;
        try {
            node = MarketSupport.MAPPER.readTree(body).path("data").path(code);
        } catch (java.io.IOException error) {
            throw new MarketSupport.MarketException("美股日线不是 JSON");
        }
        JsonNode bars = node.path("day");
        if (!bars.isArray()) {
            bars = node.path("qfqday");
        }
        if (!bars.isArray()) {
            throw new MarketSupport.MarketException("美股日线返回格式异常");
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonNode bar : bars) {
            rows.add(
                    MarketSupport.row(
                            "date", bar.path(0).asText(""),
                            "open", MarketSupport.number(bar.path(1).asText(null)),
                            "close", MarketSupport.number(bar.path(2).asText(null))));
        }
        return rows;
    }

    private Map<String, String> hkRows() {
        String body =
                client.get(HK_URL, GB18030, Map.of("User-Agent", MarketSupport.USER_AGENT, "Referer", "https://finance.sina.com.cn/"));
        Map<String, String> rows = new LinkedHashMap<>();
        Matcher matcher = HK_ROW.matcher(body);
        while (matcher.find()) {
            rows.put(matcher.group(1), matcher.group(2));
        }
        return rows;
    }

    private static String[] split(String row) {
        return row == null ? null : row.split(",");
    }

    private static String message(RuntimeException error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    record Overseas(List<Map<String, Object>> items, Map<String, String> errors) {}
}
