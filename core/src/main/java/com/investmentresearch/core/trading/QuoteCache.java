package com.investmentresearch.core.trading;

import com.investmentresearch.core.domain.trade.Quote;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 页面读的「最近一次轮询报价」；进程内缓存，不请求上游。 */
public final class QuoteCache {
    private volatile List<Quote> latest = List.of();
    private volatile Map<String, Quote> byCode = Map.of();

    public void update(List<Quote> quotes) {
        List<Quote> rows = quotes == null ? List.of() : List.copyOf(quotes);
        Map<String, Quote> index = new LinkedHashMap<>();
        for (Quote quote : rows) {
            index.put(quote.code(), quote);
        }
        latest = rows;
        byCode = Map.copyOf(index);
    }

    public List<Quote> latest() {
        return latest;
    }

    public Quote byCode(String code) {
        return byCode.get(code);
    }
}
