package com.investmentresearch.data.adapter.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.investmentresearch.core.domain.trade.Quote;
import com.investmentresearch.data.upstream.UpstreamClient;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class SinaStockQuoteAdapterTest {
    // 真实返回是 33 字段：0-9 基础行情，10-29 五档买卖盘，30 日期，31 时间，32 占位
    private static final String BODY =
            "var hq_str_sh600000=\"浦发银行,9.90,9.80,10.10,10.20,9.70,10.09,10.10,1234567,123456789.000,"
                    + "1000,10.09,2000,10.08,3000,10.07,4000,10.06,5000,10.05,"
                    + "600,10.10,700,10.11,800,10.12,900,10.13,1000,10.14,"
                    + "2026-11-07,15:00:00,00\";\n"
                    + "var hq_str_sz000001=\"\";\n";

    @Test
    void parses_quotes_and_skips_empty_rows() {
        FakeClient client = new FakeClient(BODY);

        List<Quote> quotes = new SinaStockQuoteAdapter(client).quotes(List.of("600000", "000001"));

        assertThat(quotes).hasSize(1);
        Quote quote = quotes.get(0);
        assertThat(quote.code()).isEqualTo("600000");
        assertThat(quote.name()).isEqualTo("浦发银行");
        assertThat(quote.priceCents()).isEqualTo(1010);
        assertThat(quote.prevCloseCents()).isEqualTo(980);
        assertThat(quote.openCents()).isEqualTo(990);
        assertThat(quote.highCents()).isEqualTo(1020);
        assertThat(quote.lowCents()).isEqualTo(970);
        assertThat(quote.quoteDate()).isEqualTo("2026-11-07");
        assertThat(quote.quoteTime()).isEqualTo("15:00:00");
        assertThat(quote.tradable()).isTrue();
        assertThat(client.urls).containsExactly("https://hq.sinajs.cn/list=sh600000,sz000001");
    }

    @Test
    void empty_codes_make_no_request() {
        FakeClient client = new FakeClient(BODY);

        assertThat(new SinaStockQuoteAdapter(client).quotes(List.of())).isEmpty();
        assertThat(client.urls).isEmpty();
    }

    @Test
    void more_than_twenty_codes_are_rejected() {
        FakeClient client = new FakeClient(BODY);
        List<String> codes =
                IntStream.range(0, 21).mapToObj(i -> String.format("6000%02d", i)).toList();

        assertThatThrownBy(() -> new SinaStockQuoteAdapter(client).quotes(codes))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(client.urls).isEmpty();
    }

    @Test
    void beijing_exchange_codes_are_skipped() {
        FakeClient client = new FakeClient(BODY);

        assertThat(new SinaStockQuoteAdapter(client).quotes(List.of("430047", "830799")))
                .isEmpty();
        assertThat(client.urls).isEmpty();
    }

    private static final class FakeClient implements UpstreamClient {
        private final String body;
        private final List<String> urls = new ArrayList<>();

        private FakeClient(String body) {
            this.body = body;
        }

        @Override
        public String get(String url, Map<String, String> headers) {
            throw new AssertionError("应使用带字符集的 get");
        }

        @Override
        public String get(String url, Charset charset, Map<String, String> headers) {
            assertThat(charset).isEqualTo(Charset.forName("GB18030"));
            urls.add(url);
            return body;
        }
    }
}
