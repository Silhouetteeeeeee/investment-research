package com.investmentresearch.core.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.investmentresearch.core.domain.StockRef;
import com.investmentresearch.core.port.CompanyDirectory;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CompanyResolverTest {

    private static final List<StockRef> STOCKS =
            List.of(
                    new StockRef("605499", "东鹏饮料"),
                    new StockRef("600519", "贵州茅台"),
                    new StockRef("600600", "青岛啤酒"),
                    new StockRef("000858", "五 粮 液"),
                    new StockRef("601318", "中国平安"));

    private final CompanyResolver resolver = new CompanyResolver(new FakeDirectory());

    @Test
    void six_digit_sh_prefix_and_suffix_are_accepted() {
        for (String value : List.of("605499", "SH605499", "sh605499", "605499.SH")) {
            ResolvedCompany resolved = resolver.resolve(value, null);

            assertThat(resolved.code()).as(value).isEqualTo("605499");
            assertThat(resolved.error()).as(value).isNull();
            assertThat(resolved.candidates()).as(value).isEmpty();
        }
    }

    @Test
    void exact_name_wins() {
        ResolvedCompany resolved = resolver.resolve("东鹏饮料", null);

        assertThat(resolved.code()).isEqualTo("605499");
        assertThat(resolved.error()).isNull();
        assertThat(resolved.candidates()).isEmpty();
    }

    @Test
    void fallback_is_used_when_query_missing() {
        assertThat(resolver.resolve("", "605499").code()).isEqualTo("605499");
        assertThat(resolver.resolve(null, "SH605499").code()).isEqualTo("605499");
        assertThat(resolver.resolve("  ", "东鹏饮料").code()).isEqualTo("605499");
    }

    @Test
    void empty_query_and_fallback_returns_error() {
        ResolvedCompany resolved = resolver.resolve(null, " ");

        assertThat(resolved.code()).isNull();
        assertThat(resolved.error()).isEqualTo("未指定公司：请先给出股票代码或简称");
        assertThat(resolved.candidates()).isEmpty();
    }

    @Test
    void unknown_name_returns_candidates_message() {
        ResolvedCompany resolved = resolver.resolve("茅台", null);

        assertThat(resolved.code()).isNull();
        assertThat(resolved.error()).isEqualTo("没有找到「茅台」，你是不是指：贵州茅台(600519)");
        assertThat(resolved.candidates()).containsExactly(new StockRef("600519", "贵州茅台"));
    }

    @Test
    void unknown_name_without_candidates_returns_plain_not_found() {
        ResolvedCompany resolved = resolver.resolve("不存在的公司", null);

        assertThat(resolved.code()).isNull();
        assertThat(resolved.error()).isEqualTo("没有找到「不存在的公司」");
        assertThat(resolved.candidates()).isEmpty();
    }

    private static final class FakeDirectory implements CompanyDirectory {
        @Override
        public Optional<StockRef> byCode(String code) {
            return STOCKS.stream().filter(stock -> stock.code().equals(code)).findFirst();
        }

        @Override
        public Optional<StockRef> byName(String name) {
            return STOCKS.stream().filter(stock -> stock.name().equals(name)).findFirst();
        }

        @Override
        public List<StockRef> all() {
            return STOCKS;
        }
    }
}
