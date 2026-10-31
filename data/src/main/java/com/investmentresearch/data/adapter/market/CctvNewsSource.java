package com.investmentresearch.data.adapter.market;

import com.investmentresearch.data.upstream.UpstreamClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/** 新闻联播文字稿（{@code ak.news_cctv}）：先取当日目录页，再逐条抓正文。 */
final class CctvNewsSource {
    private static final String DAY = "https://tv.cctv.com/lm/xwlb/day/%s.shtml";

    private final UpstreamClient client;

    CctvNewsSource(UpstreamClient client) {
        this.client = client;
    }

    /** {@code day} 是 {@code yyyyMMdd}；单条正文取不到就跳过，与 akshare 的 try/except 一致。 */
    List<Map<String, Object>> rows(String day) {
        Document index = Jsoup.parse(client.get(String.format(DAY, day), headers()));
        List<Element> items = index.select("li");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Element item : items.subList(Math.min(1, items.size()), items.size())) {
            Element link = item.selectFirst("a[href]");
            if (link == null) {
                continue;
            }
            Document page;
            try {
                page = Jsoup.parse(client.get(link.absUrl("href"), headers()));
            } catch (RuntimeException error) {
                continue;
            }
            rows.add(
                    MarketSupport.row(
                            "date", day,
                            "title", title(first(page, "h3", "div.tit")),
                            "content", content(first(page, "div.cnt_bd", "div.content_area"))));
        }
        return rows;
    }

    private static Map<String, String> headers() {
        return Map.of("User-Agent", MarketSupport.USER_AGENT);
    }

    private static String first(Document page, String primary, String fallback) {
        Element element = page.selectFirst(primary);
        if (element == null) {
            element = page.selectFirst(fallback);
        }
        return element == null ? "" : element.text();
    }

    /** 标题去掉 {@code [视频]} 前缀。 */
    private static String title(String text) {
        return trimChars(trimChars(text, "[视频]"), " \n").replace("\n", " ");
    }

    /** 正文去掉“央视网消息(新闻联播)：”这类固定开头。 */
    private static String content(String text) {
        String value = trimChars(text, "央视网消息(新闻联播)：");
        value = trimChars(value, "央视网消息（新闻联播）：");
        value = trimChars(value, "(新闻联播)：");
        return trimChars(value, " \n").replace("\n", " ");
    }

    /** Python 的 {@code str.strip(chars)}：按字符集合从两端剥离，不是按子串。 */
    static String trimChars(String text, String chars) {
        int start = 0;
        int end = text.length();
        while (start < end && chars.indexOf(text.charAt(start)) >= 0) {
            start++;
        }
        while (end > start && chars.indexOf(text.charAt(end - 1)) >= 0) {
            end--;
        }
        return text.substring(start, end);
    }
}
