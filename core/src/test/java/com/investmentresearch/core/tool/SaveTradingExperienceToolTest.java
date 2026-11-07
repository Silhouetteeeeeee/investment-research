package com.investmentresearch.core.tool;

import static org.assertj.core.api.Assertions.assertThat;

import com.investmentresearch.core.domain.ToolResult;
import com.investmentresearch.core.domain.trade.TradingPhase;
import com.investmentresearch.core.trading.TradingContext;
import com.investmentresearch.core.trading.TradingExperience;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SaveTradingExperienceToolTest {
    private static final String DATE = "2026-11-07";
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-11-07T07:05:00Z"), ZoneId.of("Asia/Shanghai"));

    @TempDir Path dir;

    private TradingContext context;
    private TradingExperience experience;
    private SaveTradingExperienceTool tool;

    @BeforeEach
    void setUp() {
        context = new TradingContext();
        experience = new TradingExperience(dir.resolve("trading"), CLOCK);
        tool = new SaveTradingExperienceTool(experience, context, new ToolSchemas(Map.of()));
    }

    @Test
    void only_the_close_phase_may_write_the_experience() {
        ToolResult outside = tool.execute(Map.of("content", "# 经验"), null);
        assertThat(outside.ok()).isFalse();
        assertThat(outside.error()).contains("收盘");

        context.begin(DATE, TradingPhase.PRE_MARKET);
        ToolResult preMarket = tool.execute(Map.of("content", "# 经验"), null);
        assertThat(preMarket.ok()).isFalse();
        assertThat(experience.read()).isEmpty();
    }

    @Test
    void close_phase_overwrites_the_document_and_reports_size_and_time() {
        context.begin(DATE, TradingPhase.CLOSE);

        ToolResult result = tool.execute(Map.of("content", "# 交易经验\n\n- 止损后不当天回补"), null);

        assertThat(result.ok()).isTrue();
        assertThat(experience.read()).isEqualTo("# 交易经验\n\n- 止损后不当天回补");
        assertThat(result.data())
                .containsEntry("saved", true)
                .containsEntry("date", DATE);
        assertThat(result.data().get("updated_at")).isNotNull();
    }

    @Test
    void blank_and_oversized_content_fail_without_touching_the_document() {
        context.begin(DATE, TradingPhase.CLOSE);

        ToolResult blank = tool.execute(Map.of("content", "  "), null);
        assertThat(blank.ok()).isFalse();
        assertThat(blank.error()).contains("不能为空");

        ToolResult huge =
                tool.execute(
                        Map.of("content", "经".repeat(TradingExperience.MAX_CHARS + 1)), null);
        assertThat(huge.ok()).isFalse();
        assertThat(huge.error()).contains("20000");
        assertThat(experience.read()).isEmpty();
    }
}
