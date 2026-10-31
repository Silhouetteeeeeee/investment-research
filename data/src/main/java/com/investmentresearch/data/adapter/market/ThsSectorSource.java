package com.investmentresearch.data.adapter.market;

import com.investmentresearch.data.upstream.UpstreamClient;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

/**
 * 同花顺行业板块一览（{@code ak.stock_board_industry_summary_ths}）。
 *
 * <p>akshare 走的是 {@code /ajax/1/} 接口，没有 {@code v} cookie 会 401；普通页面分页
 * 不需要 cookie，直接解析 HTML 表格即可，少移植一份 39KB 的混淆 JS。
 */
final class ThsSectorSource {
    private static final Charset GBK = Charset.forName("GBK");
    private static final String PAGE =
            "http://q.10jqka.com.cn/thshy/index/field/199112/order/desc/page/%d/";
    private static final int COLUMNS = 12;
    private static final Pattern CODE = Pattern.compile("code/(\\d+)/");

    private final UpstreamClient client;

    ThsSectorSource(UpstreamClient client) {
        this.client = client;
    }

    List<Map<String, Object>> rows() {
        Document first = Jsoup.parse(fetch(1));
        List<Map<String, Object>> rows = new ArrayList<>(parse(first));
        String info = first.select(".page_info").text();
        int pages = pageCount(info);
        for (int page = 2; page <= pages; page++) {
            rows.addAll(parse(Jsoup.parse(fetch(page))));
        }
        if (rows.isEmpty()) {
            throw new MarketSupport.MarketException("同花顺行业板块返回空表");
        }
        return rows;
    }

    private String fetch(int page) {
        return client.get(
                String.format(PAGE, page), GBK, Map.of("User-Agent", MarketSupport.USER_AGENT));
    }

    /** {@code "1/2"} → 2；读不到就只取首页。 */
    private static int pageCount(String info) {
        int slash = info.indexOf('/');
        Double pages = slash < 0 ? null : MarketSupport.number(info.substring(slash + 1));
        return pages == null ? 1 : (int) Math.max(1, Math.min(60, pages));
    }

    /** 板块链接里的 {@code /thshy/detail/code/881121/} 就是板块指数代码，取日线要用。 */
    private static String code(Element cell) {
        Element link = cell.selectFirst("a[href*=code/]");
        if (link == null) {
            return "";
        }
        Matcher matcher = CODE.matcher(link.attr("href"));
        return matcher.find() ? matcher.group(1) : "";
    }

    /** 列：序号, 板块, 涨跌幅, 总成交量, 总成交额, 净流入, 上涨家数, 下跌家数, 均价, 领涨股, 领涨股-最新价, 领涨股-涨跌幅。 */
    private static List<Map<String, Object>> parse(Document doc) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Element row : doc.select("tr")) {
            Elements cells = row.select("td");
            if (cells.size() < COLUMNS || MarketSupport.number(cells.get(0).text()) == null) {
                continue;
            }
            rows.add(
                    MarketSupport.row(
                            "代码", code(cells.get(1)),
                            "板块", cells.get(1).text().strip(),
                            "涨跌幅", MarketSupport.number(cells.get(2).text()),
                            "总成交量", MarketSupport.number(cells.get(3).text()),
                            "总成交额", MarketSupport.number(cells.get(4).text()),
                            "净流入", MarketSupport.number(cells.get(5).text()),
                            "上涨家数", MarketSupport.number(cells.get(6).text()),
                            "下跌家数", MarketSupport.number(cells.get(7).text()),
                            "均价", MarketSupport.number(cells.get(8).text()),
                            "领涨股", cells.get(9).text().strip(),
                            "领涨股-最新价", MarketSupport.number(cells.get(10).text()),
                            "领涨股-涨跌幅", MarketSupport.number(cells.get(11).text())));
        }
        return rows;
    }
}
