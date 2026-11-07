package com.investmentresearch.core.tool;

import static org.assertj.core.api.Assertions.assertThat;

import com.investmentresearch.core.domain.ToolResult;
import com.investmentresearch.core.domain.trade.TradingPhase;
import com.investmentresearch.core.trading.TradingContext;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SaveTradingJournalToolTest {
    private static final String DATE = "2026-11-07";

    private TradingToolTestSupport.InMemoryRepo repo;
    private TradingContext context;
    private SaveTradingJournalTool tool;

    @BeforeEach
    void setUp() {
        repo = new TradingToolTestSupport.InMemoryRepo();
        context = new TradingContext();
        tool = new SaveTradingJournalTool(repo, context, new ToolSchemas(Map.of()));
    }

    @Test
    void premarket_is_rejected() {
        context.begin(DATE, TradingPhase.PRE_MARKET);

        ToolResult result = tool.execute(Map.of("text", "盘前只想计划"), null);

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("盘前不能写复盘");
        assertThat(repo.journals(DATE)).isEmpty();
    }

    @Test
    void midday_and_close_write_a_journal_entry() {
        context.begin(DATE, TradingPhase.MIDDAY);
        assertThat(tool.execute(Map.of("text", "上午按计划成交一笔"), null).ok()).isTrue();
        assertThat(repo.journals(DATE))
                .singleElement()
                .satisfies(
                        journal -> {
                            assertThat(journal.text()).isEqualTo("上午按计划成交一笔");
                            assertThat(journal.phase()).isEqualTo(TradingPhase.MIDDAY);
                            assertThat(journal.tradeDate()).isEqualTo(DATE);
                        });

        context.begin(DATE, TradingPhase.CLOSE);
        assertThat(tool.execute(Map.of("text", "收盘当日盈亏 +1.2%"), null).ok()).isTrue();
        assertThat(repo.journals(DATE)).hasSize(2);
    }

    @Test
    void blank_text_is_rejected() {
        context.begin(DATE, TradingPhase.CLOSE);

        ToolResult result = tool.execute(Map.of("text", "   "), null);

        assertThat(result.ok()).isFalse();
        assertThat(repo.journals(DATE)).isEmpty();
    }
}
