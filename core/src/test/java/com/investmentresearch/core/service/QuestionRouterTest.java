package com.investmentresearch.core.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.investmentresearch.core.domain.StockRef;
import com.investmentresearch.core.port.CompanyDirectory;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class QuestionRouterTest {

    private static final List<StockRef> STOCKS =
            List.of(
                    new StockRef("605499", "东鹏饮料"),
                    new StockRef("600519", "贵州茅台"),
                    new StockRef("601318", "中国平安"),
                    new StockRef("000001", "平安银行"),
                    new StockRef("000002", "平安"),
                    new StockRef("600000", " "),
                    new StockRef("600001", " 浦发银行 "));

    private final QuestionRouter router = new QuestionRouter(new FakeDirectory(STOCKS));

    @Test
    void six_digit_code_in_text_wins_over_names() {
        Route route = router.route(null, null, "贵州茅台和605499哪个好");

        assertThat(route.code()).isEqualTo("605499");
        assertThat(route.name()).isEqualTo("东鹏饮料");
        assertThat(route.reset()).isTrue();
    }

    @Test
    void six_digit_code_not_in_the_directory_is_ignored() {
        assertThat(router.route(null, null, "订单123456怎么办").code()).isNull();
        // 七位数字不算六位代码
        assertThat(router.route(null, null, "订单1234567怎么办").code()).isNull();
    }

    @Test
    void unknown_code_falls_through_to_name_match() {
        Route route = router.route(null, null, "123456 贵州茅台");

        assertThat(route.code()).isEqualTo("600519");
        assertThat(route.name()).isEqualTo("贵州茅台");
    }

    @Test
    void earliest_name_wins_and_longer_name_breaks_the_tie() {
        // 「平安银行」与「平安」都从 0 开始，名字更长者胜
        assertThat(router.route(null, null, "平安银行怎么样").name()).isEqualTo("平安银行");
        // 先出现者胜：贵州茅台在 0，中国平安在 5
        assertThat(router.route(null, null, "贵州茅台和中国平安").name()).isEqualTo("贵州茅台");
    }

    @Test
    void matched_name_is_the_stripped_name_from_the_directory() {
        Route route = router.route(null, null, "浦发银行怎么样");

        assertThat(route.code()).isEqualTo("600001");
        assertThat(route.name()).isEqualTo("浦发银行");
    }

    @Test
    void company_mentioned_in_text_beats_market_words() {
        Route route = router.route(null, null, "贵州茅台在A股大盘里怎么样");

        assertThat(route.code()).isEqualTo("600519");
        assertThat(route.reset()).isTrue();
    }

    @Test
    void market_question_without_current_company_resets_to_market() {
        Route route = router.route(null, null, "大盘怎么样");

        assertThat(route.code()).isEqualTo(QuestionRouter.MARKET_CODE);
        assertThat(route.name()).isEqualTo(QuestionRouter.MARKET_NAME);
        assertThat(route.reset()).isTrue();
    }

    @Test
    void market_question_keeps_current_company_when_it_uses_anaphora() {
        Route route = router.route("600519", "贵州茅台", "它的走势怎么样");

        assertThat(route.code()).isEqualTo("600519");
        assertThat(route.name()).isEqualTo("贵州茅台");
        assertThat(route.reset()).isFalse();
    }

    @Test
    void market_question_switches_away_when_it_does_not_refer_back() {
        Route route = router.route("600519", "贵州茅台", "大盘走势怎么样");

        assertThat(route.code()).isEqualTo(QuestionRouter.MARKET_CODE);
        assertThat(route.reset()).isTrue();
    }

    @Test
    void market_conversation_does_not_reset_again() {
        Route route = router.route(QuestionRouter.MARKET_CODE, QuestionRouter.MARKET_NAME, "大盘怎么样");

        assertThat(route.code()).isEqualTo(QuestionRouter.MARKET_CODE);
        assertThat(route.reset()).isFalse();
    }

    @Test
    void plain_question_keeps_the_current_company() {
        Route route = router.route("600519", "贵州茅台", "营收怎么样");

        assertThat(route.code()).isEqualTo("600519");
        assertThat(route.name()).isEqualTo("贵州茅台");
        assertThat(route.reset()).isFalse();
    }

    private static final class FakeDirectory implements CompanyDirectory {
        private final List<StockRef> stocks;

        FakeDirectory(List<StockRef> stocks) {
            this.stocks = stocks;
        }

        @Override
        public Optional<StockRef> byCode(String code) {
            return stocks.stream().filter(stock -> stock.code().equals(code)).findFirst();
        }

        @Override
        public Optional<StockRef> byName(String name) {
            return stocks.stream().filter(stock -> stock.name().equals(name)).findFirst();
        }

        @Override
        public List<StockRef> all() {
            return stocks;
        }
    }
}
