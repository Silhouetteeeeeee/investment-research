package com.investmentresearch.data.adapter.market;

import com.investmentresearch.core.domain.trade.Quote;
import com.investmentresearch.core.port.QuotePort;
import com.investmentresearch.data.upstream.UpstreamClient;
import java.nio.charset.Charset;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 新浪 {@code hq.sinajs.cn} 批量快照。盘中的执行器每 10 秒调一次，
 * 一次请求最多 20 只，够覆盖 5 个持仓 + 当日候选。
 */
@Component
public class SinaStockQuoteAdapter implements QuotePort {
    static final int MAX_BATCH = 20;
    private static final Charset GB18030 = Charset.forName("GB18030");
    private static final Pattern LINE = Pattern.compile("hq_str_([a-z]{2}\\d{6})=\"([^\"]*)\"");
    private static final Map<String, String> HEADERS =
            Map.of("Referer", "https://finance.sina.com.cn/");

    private final UpstreamClient client;
    private final Clock clock;

    @Autowired
    public SinaStockQuoteAdapter(UpstreamClient client) {
        this(client, Clock.systemDefaultZone());
    }

    public SinaStockQuoteAdapter(UpstreamClient client, Clock clock) {
        this.client = client;
        this.clock = clock;
    }

    @Override
    public List<Quote> quotes(List<String> codes) {
        List<String> symbols = new ArrayList<>();
        if (codes != null) {
            for (String code : new LinkedHashSet<>(codes)) {
                String symbol = symbol(code);
                if (symbol != null) {
                    symbols.add(symbol);
                }
            }
        }
        if (symbols.isEmpty()) {
            return List.of();
        }
        if (symbols.size() > MAX_BATCH) {
            throw new IllegalArgumentException("一次最多查询 " + MAX_BATCH + " 只股票");
        }

        String body =
                client.get(
                        "https://hq.sinajs.cn/list=" + String.join(",", symbols),
                        GB18030,
                        HEADERS);
        List<Quote> quotes = new ArrayList<>();
        Matcher matcher = LINE.matcher(body);
        while (matcher.find()) {
            Quote quote = parse(matcher.group(1), matcher.group(2));
            if (quote != null) {
                quotes.add(quote);
            }
        }
        return quotes;
    }

    /** 只做沪深主板/创业板；北交所（4/8 开头）没有对应的 hq_str 前缀。 */
    private static String symbol(String code) {
        if (code == null || code.length() != 6) {
            return null;
        }
        if (code.startsWith("6")) {
            return "sh" + code;
        }
        if (code.startsWith("0") || code.startsWith("3")) {
            return "sz" + code;
        }
        return null;
    }

    private Quote parse(String symbol, String raw) {
        String[] fields = raw.split(",", -1);
        if (fields.length < 32) {
            return null;
        }
        Double price = number(fields[3]);
        Double previous = number(fields[2]);
        if (price == null || previous == null || price <= 0 || previous <= 0) {
            return null;
        }
        return new Quote(
                symbol.substring(2),
                fields[0].strip(),
                cents(price),
                cents(previous),
                cents(number(fields[1])),
                cents(number(fields[4])),
                cents(number(fields[5])),
                fields[30].strip(),
                fields[31].strip(),
                clock.millis());
    }

    private static Double number(String value) {
        try {
            return value == null || value.isBlank() ? null : Double.valueOf(value.strip());
        } catch (NumberFormatException error) {
            return null;
        }
    }

    private static long cents(Double value) {
        return value == null ? 0 : Math.round(value * 100);
    }
}
