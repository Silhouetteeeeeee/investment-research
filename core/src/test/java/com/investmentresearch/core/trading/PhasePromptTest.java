package com.investmentresearch.core.trading;

import static org.assertj.core.api.Assertions.assertThat;

import com.investmentresearch.core.domain.trade.TradingPhase;
import com.investmentresearch.core.tool.TradingToolTestSupport;
import org.junit.jupiter.api.Test;

class PhasePromptTest {
    private static final String DATE = "2026-11-07";

    private final TradingToolTestSupport.InMemoryRepo repo =
            new TradingToolTestSupport.InMemoryRepo();

    @Test
    void experience_is_injected_into_every_phase() {
        String prompt =
                PhasePrompt.userMessage(
                        repo, TradingPhase.PRE_MARKET, DATE, "# 交易经验\n- 止损后不要当天回补");

        assertThat(prompt).contains("交易经验").contains("止损后不要当天回补");
    }

    @Test
    void empty_experience_is_stated_explicitly() {
        String prompt = PhasePrompt.userMessage(repo, TradingPhase.MIDDAY, DATE, "");

        assertThat(prompt).contains("暂无经验");
    }

    @Test
    void close_phase_asks_to_merge_lessons_into_the_document() {
        String prompt = PhasePrompt.userMessage(repo, TradingPhase.CLOSE, DATE, "旧经验");

        assertThat(prompt).contains("save_trading_experience").contains("合并");
    }
}
