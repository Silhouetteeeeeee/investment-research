package com.investmentresearch.data.adapter.eastmoney;

import static org.assertj.core.api.Assertions.assertThat;

import com.investmentresearch.data.persistence.ResearchCacheStore;
import com.investmentresearch.data.persistence.StockMapper;
import com.investmentresearch.data.upstream.UpstreamClient;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 回归测试：东财 datacenter 的 {@code sortTypes} 与 {@code sortColumns} 是两个独立参数。
 * 历史上适配器把「列:方向」当一整个字符串传，实际发出的是
 * {@code sortTypes=NOTICE_DATE&sortColumns=-1}，上游直接回「-1 参数校验失败」；
 * 缓存在快照里的测试只读缓存，抓不到这条路径。
 */
class DatacenterSortQueryTest {
    private static final String ENVELOPE = "{\"success\":true,\"result\":{\"pages\":1,\"data\":[]}}";
    private static final String CODE = "600600";
    private static final String ENDPOINT = "http://test/api/data/v1/get";

    @TempDir Path tempDir;
    private final List<String> urls = new ArrayList<>();
    private EastmoneyTestSupport.Db db;
    private ResearchCacheStore store;

    @BeforeEach
    void setUp() throws Exception {
        String url = EastmoneyTestSupport.copySnapshot(tempDir);
        db = EastmoneyTestSupport.openDb(url);
        store = EastmoneyTestSupport.cacheStore(url);
        for (String table :
                List.of(
                        "company_forecasts",
                        "company_pledges",
                        "company_lockups",
                        "company_insider_trades",
                        "company_research",
                        "company_buybacks")) {
            EastmoneyTestSupport.exec(db.session(), "delete from " + table + " where code = ?", CODE);
        }
    }

    @Test
    void forecast_sends_sort_types_then_sort_columns() {
        new ForecastAdapter(store, mapper(), capture(), ENDPOINT, EastmoneyTestSupport.CLOCK_END)
                .forecasts(CODE, 3);
        assertSort("-1", "NOTICE_DATE");
    }

    @Test
    void pledge_sends_sort_types_then_sort_columns() {
        new PledgeAdapter(store, mapper(), capture(), ENDPOINT, EastmoneyTestSupport.CLOCK_END)
                .pledges(CODE, 3);
        assertSort("-1", "TRADE_DATE");
    }

    @Test
    void lockup_sends_sort_types_then_sort_columns() {
        new LockupAdapter(store, mapper(), capture(), ENDPOINT, EastmoneyTestSupport.CLOCK_END)
                .lockups(CODE, 3, 12);
        assertSort("-1", "FREE_DATE");
    }

    @Test
    void insider_trade_sends_sort_types_then_sort_columns() {
        new InsiderTradeAdapter(store, mapper(), capture(), ENDPOINT, EastmoneyTestSupport.CLOCK_END)
                .insiderTrades(CODE, 3);
        assertSort("-1,1,1", "CHANGE_DATE,SECURITY_CODE,PERSON_NAME");
    }

    @Test
    void research_sends_sort_types_then_sort_columns() {
        new ResearchAdapter(store, mapper(), capture(), ENDPOINT, EastmoneyTestSupport.CLOCK_END)
                .research(CODE, 2);
        assertSort("-1", "NOTICE_DATE");
    }

    @Test
    void buyback_sends_sort_types_then_sort_columns() {
        new BuybackAdapter(store, mapper(), capture(), ENDPOINT, EastmoneyTestSupport.CLOCK_END)
                .buybacks(CODE, 5);
        assertSort("-1,-1,1", "UPD,DIM_DATE,DIM_SCODE");
    }

    private StockMapper mapper() {
        return db.session().getMapper(StockMapper.class);
    }

    private void assertSort(String types, String columns) {
        assertThat(urls).isNotEmpty();
        String url = URLDecoder.decode(urls.get(0), StandardCharsets.UTF_8);
        assertThat(url)
                .contains("sortTypes=" + types + "&sortColumns=" + columns);
    }

    private UpstreamClient capture() {
        return new UpstreamClient() {
            @Override
            public String get(String url, Map<String, String> headers) {
                urls.add(url);
                return ENVELOPE;
            }

            @Override
            public String get(String url, Charset charset, Map<String, String> headers) {
                return get(url, headers);
            }
        };
    }
}
