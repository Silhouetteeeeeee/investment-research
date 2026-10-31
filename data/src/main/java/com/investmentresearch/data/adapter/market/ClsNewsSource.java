package com.investmentresearch.data.adapter.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.investmentresearch.data.upstream.UpstreamClient;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 财联社电报（{@code ak.stock_info_global_cls}）：标题按北京时间拆成日期与时刻。 */
final class ClsNewsSource {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final String URL = "https://www.cls.cn/v1/roll/get_roll_list?";

    private final UpstreamClient client;

    ClsNewsSource(UpstreamClient client) {
        this.client = client;
    }

    List<Map<String, Object>> rows() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("app", "CailianpressWeb");
        params.put("category", "");
        params.put("last_time", String.valueOf(Instant.now().getEpochSecond()));
        params.put("os", "web");
        params.put("refresh_type", "1");
        params.put("rn", "20");
        params.put("sv", "8.4.6");
        // 财联社的签名：sign = md5(sha1(query))；params 的顺序必须与 Python 一致
        String url = URL + query(params) + "&sign=" + md5(sha1(query(params)));

        JsonNode root;
        try {
            root =
                    MarketSupport.MAPPER.readTree(
                            client.get(url, Map.of("Referer", "https://www.cls.cn/telegraph")));
        } catch (IOException error) {
            throw new MarketSupport.MarketException("财联社响应不是 JSON");
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonNode item : root.path("data").path("roll_data")) {
            Double epoch = MarketSupport.number(item.path("ctime").asText(null));
            String date = "";
            String time = "";
            if (epoch != null) {
                ZonedDateTime stamp =
                        Instant.ofEpochSecond(epoch.longValue()).atZone(SHANGHAI);
                date = stamp.format(DATE);
                time = stamp.format(TIME);
            }
            rows.add(
                    MarketSupport.row(
                            "标题", item.path("title").asText("").strip(),
                            "内容", item.path("content").asText("").strip(),
                            "发布日期", date,
                            "发布时间", time));
        }
        return rows;
    }

    private static String query(Map<String, String> params) {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (out.length() > 0) {
                out.append('&');
            }
            out.append(entry.getKey())
                    .append('=')
                    .append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
        }
        return out.toString();
    }

    private static String sha1(String text) {
        return digest("SHA-1", text);
    }

    private static String md5(String text) {
        return digest("MD5", text);
    }

    private static String digest(String algorithm, String text) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance(algorithm)
                                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(algorithm + " 不可用", error);
        }
    }
}
