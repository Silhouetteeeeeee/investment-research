package com.investmentresearch.data.adapter.market;

import com.investmentresearch.data.upstream.UpstreamClient;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 新浪 {@code hq.sinajs.cn} 指数快照，替代 Python 的 {@code ak.stock_zh_index_spot_sina()}
 * （后者要翻 8 页分类列表，这里一次请求拿齐 7 个宽基）。
 */
final class SinaIndexSource {
    static final List<String> CODES =
            List.of("sh000001", "sz399001", "sz399006", "sh000300", "sh000905", "sh000852", "sh000688");
    static final String URL = "https://hq.sinajs.cn/list=" + String.join(",", CODES);
    private static final Charset GB18030 = Charset.forName("GB18030");
    private static final Pattern LINE = Pattern.compile("hq_str_([a-z]{2}\\d{6})=\"([^\"]*)\"");

    private final UpstreamClient client;

    SinaIndexSource(UpstreamClient client) {
        this.client = client;
    }

    /** 字段顺序：名称, 今开, 昨收, 现价, 最高, 最低, 买一, 卖一, 成交量, 成交额。 */
    List<Map<String, Object>> rows() {
        String body =
                client.get(URL, GB18030, Map.of("Referer", "https://finance.sina.com.cn/"));
        List<Map<String, Object>> rows = new ArrayList<>();
        Matcher matcher = LINE.matcher(body);
        while (matcher.find()) {
            String[] fields = matcher.group(2).split(",", -1);
            if (fields.length < 10) {
                continue;
            }
            Double price = MarketSupport.number(fields[3]);
            if (price == null) {
                continue;
            }
            Double previous = MarketSupport.number(fields[2]);
            Double change = previous == null ? null : price - previous;
            Double changePct =
                    previous == null || previous == 0 ? null : change / previous * 100;
            rows.add(
                    MarketSupport.row(
                            "代码", matcher.group(1),
                            "名称", fields[0].strip(),
                            "最新价", MarketSupport.round(price, 2),
                            "涨跌额", MarketSupport.round(change, 2),
                            "涨跌幅", MarketSupport.round(changePct, 2),
                            "今开", MarketSupport.round(MarketSupport.number(fields[1]), 2),
                            "最高", MarketSupport.round(MarketSupport.number(fields[4]), 2),
                            "最低", MarketSupport.round(MarketSupport.number(fields[5]), 2),
                            "昨收", MarketSupport.round(previous, 2),
                            "成交额", MarketSupport.number(fields[9]),
                            "成交量", MarketSupport.number(fields[8])));
        }
        if (rows.isEmpty()) {
            throw new MarketSupport.MarketException("指数行情接口返回格式异常");
        }
        return rows;
    }
}
