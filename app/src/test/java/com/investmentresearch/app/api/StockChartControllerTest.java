package com.investmentresearch.app.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.investmentresearch.core.domain.KlineBar;
import com.investmentresearch.core.port.KlinePort;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 路径校验与 days 钳位：面板传什么都不会把上游打爆。 */
class StockChartControllerTest {
    private final KlinePort port =
            (code, days) -> List.of(new KlineBar("2026-10-30", 1.0, 1.1, 1.2, 0.9, 100.0, 110.0));

    private final StockChartController controller = new StockChartController(port);

    @Test
    void days_is_clamped_to_supported_range() {
        assertThat(controller.kline("600519", "5").getBody()).containsEntry("days", 20);
        assertThat(controller.kline("600519", "9999").getBody()).containsEntry("days", 500);
        assertThat(controller.kline("600519", "abc").getBody()).containsEntry("days", 120);
        assertThat(controller.kline("600519", "60").getBody()).containsEntry("days", 60);
    }

    @Test
    void non_six_digit_code_is_404() {
        assertThat(controller.kline("60051", "120").getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void upstream_failure_becomes_502_with_message() {
        StockChartController broken =
                new StockChartController(
                        (code, days) -> {
                            throw new IllegalStateException("日线接口返回空数据");
                        });
        var response = broken.kline("600519", "120");
        assertThat(response.getStatusCode().value()).isEqualTo(502);
        assertThat(((Map<?, ?>) response.getBody()).get("error").toString())
                .contains("日线接口返回空数据");
    }
}
