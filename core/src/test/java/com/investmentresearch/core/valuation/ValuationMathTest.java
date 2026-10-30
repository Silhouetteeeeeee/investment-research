package com.investmentresearch.core.valuation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 终值 PE 的两条边界：永续增速追上折现率、ROIC 非正。 */
class ValuationMathTest {
    @Test
    void terminal_value_is_invalid_when_growth_reaches_discount_rate() {
        Map<String, Object> result = ValuationMath.terminalPe(12.0, 8.0, 8.0);
        assertThat(result.get("valid")).isEqualTo(false);
        assertThat(result.get("status")).isEqualTo("fail");
        assertThat(result.get("terminal_pe")).isNull();
        assertThat(messages(result))
                .contains("折现率必须高于永续增速，否则分母不为正");
    }

    @Test
    void terminal_value_handles_non_positive_roic() {
        Map<String, Object> result = ValuationMath.terminalPe(0.0, 2.0, 9.0);
        assertThat(result.get("valid")).isEqualTo(false);
        assertThat(result.get("status")).isEqualTo("fail");
        assertThat(result.get("terminal_pe")).isNull();
        assertThat(messages(result))
                .contains("ROIC 必须大于 0，否则留存率模型没有意义")
                .contains("永续增速不低于 ROIC，留存率为负");
    }

    /** Python 用 Decimal 计算；双精度在这几组输入上会把末位算歪。 */
    @Test
    void arithmetic_matches_the_decimal_reference() {
        assertThat(ValuationMath.round((Double) ValuationMath.scenario(17.5, 7.8, 5.1, 1, 87.08).get("future_value"), 2))
                .isEqualTo(18.86);
        assertThat(ValuationMath.round((Double) ValuationMath.scenario(5.27, 50.0, 48.0, 1, null).get("future_value"), 2))
                .isEqualTo(7.91);
        assertThat(ValuationMath.round((Double) ValuationMath.scenario(19.08, 50.0, 64.5, 2, null).get("future_price"), 2))
                .isEqualTo(2768.99);
        assertThat(ValuationMath.round((Double) ValuationMath.terminalPe(32.0, 25.2, 32.7).get("payout_ratio_pct"), 1))
                .isEqualTo(21.2);
        assertThat(ValuationMath.round((Double) ValuationMath.terminalPe(62.5, 31.1, 37.5).get("terminal_pe"), 1))
                .isEqualTo(7.8);
    }

    @SuppressWarnings("unchecked")
    private static List<String> messages(Map<String, Object> result) {
        return ((List<Map<String, Object>>) result.get("checks"))
                .stream().map(item -> (String) item.get("message")).toList();
    }
}
