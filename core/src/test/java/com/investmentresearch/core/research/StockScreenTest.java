package com.investmentresearch.core.research;

import static org.assertj.core.api.Assertions.assertThat;

import com.investmentresearch.core.domain.ToolResult;
import com.investmentresearch.core.domain.screen.ScreenFilter;
import com.investmentresearch.core.domain.screen.ScreenFinance;
import com.investmentresearch.core.domain.screen.ScreenQuote;
import com.investmentresearch.core.domain.screen.ScreenSort;
import com.investmentresearch.core.domain.screen.StockScreenQuery;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 参数化筛选的纯逻辑：校验、合并行情与财务、过滤、排序。 */
class StockScreenTest {
    private static final String AS_OF = "2026-11-06";
    private static final String FETCHED = "2026-11-06 16:00";
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 9);

    @Test
    void filters_are_and_and_missing_metric_fails() {
        List<ScreenQuote> quotes = List.of(quote("600001", 10.0), quote("600002", 10.0));
        List<ScreenFinance> finances =
                List.of(finance("600001", "2025-12-31", 15.0, 1.0), finance("600002", "2025-12-31", null, 1.0));

        ToolResult result =
                screen(
                        new StockScreenQuery(
                                List.of(
                                        new ScreenFilter("pe", "<=", 10),
                                        new ScreenFilter("roe", ">=", 10)),
                                List.of(),
                                20),
                        quotes,
                        finances);

        assertThat(result.ok()).isTrue();
        assertThat(codes(result)).containsExactly("600001");
    }

    @Test
    void boards_in_matches_six_digit_codes() {
        List<ScreenQuote> quotes =
                List.of(
                        quote("600000", 10.0),
                        quote("000001", 10.0),
                        quote("300750", 10.0),
                        quote("301001", 10.0),
                        quote("688001", 10.0),
                        quote("430001", 10.0));

        ToolResult result =
                screen(
                        new StockScreenQuery(
                                List.of(new ScreenFilter("boards", "in", List.of("创业板"))),
                                List.of(),
                                20),
                        quotes,
                        List.of());

        assertThat(result.ok()).isTrue();
        assertThat(codes(result)).containsExactly("300750", "301001");
    }

    @Test
    void multi_sort_puts_null_last_and_ties_fall_back_to_code() {
        List<ScreenQuote> quotes =
                List.of(quote("600003", 5.0), quote("600001", 5.0), quote("600002", 8.0), quote("600004", 5.0));
        List<ScreenFinance> finances =
                List.of(
                        finance("600001", "2025-12-31", 20.0, 1.0),
                        finance("600002", "2025-12-31", 10.0, 1.0),
                        finance("600003", "2025-12-31", 20.0, 1.0),
                        finance("600004", "2025-12-31", null, 1.0));

        ToolResult result =
                screen(
                        new StockScreenQuery(
                                List.of(new ScreenFilter("price", ">", 0)),
                                List.of(new ScreenSort("roe", "desc"), new ScreenSort("pe", "asc")),
                                20),
                        quotes,
                        finances);

        assertThat(result.ok()).isTrue();
        assertThat(codes(result)).containsExactly("600001", "600003", "600002", "600004");
    }

    @Test
    void derived_metrics_are_computed_from_finance() {
        ScreenQuote quote = quote("600519", 10.0, 123.456);
        List<ScreenFinance> finances =
                List.of(
                        new ScreenFinance(
                                "600519", "2025-12-31", 0.35, 5e9, 1_234_567_890.0, 25.0, null, null, null, 1.2,
                                "2026-03-01"),
                        new ScreenFinance(
                                "600519", "2026-09-30", 0.40, 4e9, 8e8, 30.0, null, null, null, 1.0, "2026-11-01"));

        ToolResult result =
                screen(
                        new StockScreenQuery(List.of(new ScreenFilter("price", ">", 0)), List.of(), 20),
                        List.of(quote),
                        finances);

        assertThat(result.ok()).isTrue();
        Map<String, Object> candidate = candidates(result).get(0);
        assertThat(candidate.get("ps")).isEqualTo(2.47);
        assertThat(candidate.get("net_margin")).isEqualTo(20.0);
        assertThat(candidate.get("ocf_to_profit")).isEqualTo(2.5);
        assertThat(candidate.get("roe")).isEqualTo(30.0);
        assertThat(candidate.get("report_date")).isEqualTo("2026-09-30");
        assertThat(candidate.get("ocf_positive_years")).isEqualTo(1);
    }

    @Test
    void ocf_positive_years_counts_known_annual_reports() {
        List<ScreenQuote> quotes = List.of(quote("600001", 10.0), quote("600002", 10.0), quote("600003", 10.0));
        List<ScreenFinance> finances =
                List.of(
                        finance("600001", "2023-12-31", 10.0, 1.0),
                        finance("600001", "2024-12-31", 10.0, -1.0),
                        finance("600001", "2025-12-31", 10.0, 2.0),
                        finance("600002", "2023-12-31", 10.0, 1.0),
                        finance("600002", "2024-12-31", 10.0, 1.0),
                        finance("600003", "2023-12-31", 10.0, 1.0),
                        finance("600003", "2024-12-31", 10.0, 1.0),
                        finance("600003", "2025-12-31", 10.0, 1.0));

        ToolResult two = screen(query("ocf_positive_years", ">=", 2), quotes, finances);
        assertThat(codes(two)).containsExactly("600001", "600002", "600003");
        assertThat(candidates(two).get(0).get("ocf_positive_years")).isEqualTo(2);

        // 600002 只有 2 个已知年度，条件 3 年不可用「猜测」满足
        ToolResult three = screen(query("ocf_positive_years", ">=", 3), quotes, finances);
        assertThat(codes(three)).containsExactly("600003");
    }

    @Test
    void invalid_field_lists_allowed_values() {
        ToolResult result =
                screen(
                        new StockScreenQuery(
                                List.of(new ScreenFilter("unknown", ">", 1)), List.of(), 20),
                        List.of(quote("600001", 10.0)),
                        List.of());

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("筛选失败").contains("unknown").contains("roe");
    }

    @Test
    void finance_needed_but_empty_fails() {
        ToolResult result =
                screen(
                        new StockScreenQuery(
                                List.of(new ScreenFilter("roe", ">=", 10)), List.of(), 20),
                        List.of(quote("600001", 10.0)),
                        List.of());

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("财务数据尚未同步");
    }

    @Test
    void stale_quote_data_adds_note() {
        ScreenQuote quote = new ScreenQuote(
                "600001", "浦发银行", "银行", 10.0, 1.0, 2.0, 3.0, 6.0, 0.5, 100.0, "2025-01-01");

        ToolResult result =
                StockScreen.screen(
                        new StockScreenQuery(
                                List.of(new ScreenFilter("price", ">", 0)), List.of(), 20),
                        List.of(quote),
                        List.of(),
                        "2025-01-01",
                        FETCHED,
                        LocalDate.of(2025, 1, 10));

        assertThat(result.ok()).isTrue();
        assertThat(notes(result)).contains("行情为 2025-01-01 收盘数据");
        assertThat(notes(result)).contains("缺少的指标按不满足处理", "ROE 与增速为报告期累计值");
    }

    @Test
    void malformed_null_field_fails_without_npe() {
        ToolResult result =
                screen(
                        new StockScreenQuery(
                                java.util.Arrays.asList(new ScreenFilter(null, ">", 1)), List.of(), 20),
                        List.of(quote("600001", 10.0)),
                        List.of());

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("filters");
    }

    @Test
    void limit_is_clamped_and_after_filters_counts_before_truncation() {
        List<ScreenQuote> quotes =
                List.of(
                        quote("600005", 10.0),
                        quote("600004", 10.0),
                        quote("600003", 10.0),
                        quote("600002", 10.0),
                        quote("600001", 10.0));

        ToolResult result =
                screen(
                        new StockScreenQuery(
                                List.of(new ScreenFilter("price", ">", 0)), List.of(), 2),
                        quotes,
                        List.of());

        assertThat(codes(result)).containsExactly("600001", "600002");
        assertThat(universe(result).get("after_filters")).isEqualTo(5);
    }

    @Test
    void boards_is_not_sortable() {
        ToolResult result =
                screen(
                        new StockScreenQuery(
                                List.of(new ScreenFilter("price", ">", 0)),
                                List.of(new ScreenSort("boards", "asc")),
                                20),
                        List.of(quote("600001", 10.0)),
                        List.of());

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("pe");
    }

    @Test
    void negative_or_zero_pe_pb_is_missing() {
        ScreenQuote quote = new ScreenQuote(
                "600001", "亏损股", "行业", 10.0, 1.0, 2.0, 3.0, -5.0, 0.0, 100.0, AS_OF);

        ToolResult filtered =
                screen(
                        new StockScreenQuery(
                                List.of(new ScreenFilter("pe", "<=", 10)), List.of(), 20),
                        List.of(quote),
                        List.of());
        assertThat(filtered.ok()).isTrue();
        assertThat(codes(filtered)).isEmpty();

        ToolResult displayed =
                screen(query("price", ">", 0), List.of(quote), List.of());
        Map<String, Object> candidate = candidates(displayed).get(0);
        assertThat(candidate.get("pe")).isNull();
        assertThat(candidate.get("pb")).isNull();
    }

    @Test
    void ratio_outputs_are_rounded_but_filtering_uses_raw_values() {
        ScreenQuote quote = quote("600001", 10.0);
        List<ScreenFinance> finances =
                List.of(new ScreenFinance(
                        "600001", "2025-12-31", 0.35, 5e9, 1e8, 8.0734, 33.3333, 1.2345, -2.3456, 1.2, "2026-01-01"));

        ToolResult result =
                screen(
                        new StockScreenQuery(
                                List.of(new ScreenFilter("roe", ">=", 8.0734)), List.of(), 20),
                        List.of(quote),
                        finances);

        assertThat(codes(result)).containsExactly("600001");
        Map<String, Object> candidate = candidates(result).get(0);
        assertThat(candidate.get("roe")).isEqualTo(8.07);
        assertThat(candidate.get("gross_margin")).isEqualTo(33.33);
        assertThat(candidate.get("revenue_growth")).isEqualTo(1.23);
        assertThat(candidate.get("profit_growth")).isEqualTo(-2.35);
    }

    @Test
    void illegal_board_value_fails_and_lists_legal_values() {
        ToolResult result =
                screen(
                        new StockScreenQuery(
                                List.of(new ScreenFilter("boards", "in", List.of("北交所"))), List.of(), 20),
                        List.of(quote("600001", 10.0)),
                        List.of());

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("北交所").contains("创业板").contains("其他");
    }

    @Test
    void sort_without_order_defaults_to_desc() {
        List<ScreenQuote> quotes = List.of(quote("600001", 5.0), quote("600002", 10.0));

        ToolResult result =
                screen(
                        new StockScreenQuery(
                                List.of(), java.util.Arrays.asList(new ScreenSort("pe", null)), 20),
                        quotes,
                        List.of());

        assertThat(codes(result)).containsExactly("600002", "600001");
    }

    @Test
    void equals_operator_matches_exactly() {
        List<ScreenQuote> quotes = List.of(quote("600001", 10.0), quote("600002", 10.5));

        ToolResult result = screen(query("pe", "=", 10), quotes, List.of());

        assertThat(codes(result)).containsExactly("600001");
    }

    @Test
    void finance_filter_fails_for_quote_without_finance_row() {
        List<ScreenQuote> quotes = List.of(quote("600001", 10.0), quote("600002", 10.0));
        List<ScreenFinance> finances = List.of(finance("600001", "2025-12-31", 15.0, 1.0));

        ToolResult result = screen(query("roe", ">=", 10), quotes, finances);

        assertThat(result.ok()).isTrue();
        assertThat(codes(result)).containsExactly("600001");
    }

    @Test
    void scanned_counts_only_processable_rows() {
        ScreenQuote st = new ScreenQuote("600002", "ST测试", "行业", 10.0, 1.0, 2.0, 3.0, 10.0, 1.0, 100.0, AS_OF);
        ScreenQuote noQuote = new ScreenQuote("600003", "停牌", "行业", null, 1.0, 2.0, 3.0, 10.0, 1.0, 100.0, AS_OF);
        List<ScreenQuote> quotes =
                java.util.Arrays.asList(null, quote("", 10.0), st, noQuote, quote("600001", 10.0));

        ToolResult result = screen(query("price", ">", 0), quotes, List.of());

        assertThat(codes(result)).containsExactly("600001");
        Map<String, Object> universe = universe(result);
        assertThat(universe.get("scanned")).isEqualTo(3);
        assertThat(universe.get("after_filters")).isEqualTo(1);
        @SuppressWarnings("unchecked")
        Map<String, Integer> excluded = (Map<String, Integer>) universe.get("excluded");
        assertThat(excluded).containsEntry("ST/退市", 1).containsEntry("无报价", 1);
    }

    private static StockScreenQuery query(String field, String op, Object value) {
        return new StockScreenQuery(List.of(new ScreenFilter(field, op, value)), List.of(), 20);
    }

    private static ToolResult screen(
            StockScreenQuery query, List<ScreenQuote> quotes, List<ScreenFinance> finances) {
        return StockScreen.screen(query, quotes, finances, AS_OF, FETCHED, TODAY);
    }

    private static ScreenQuote quote(String code, Double pe) {
        return quote(code, pe, 100.0);
    }

    private static ScreenQuote quote(String code, Double pe, Double totalCapYi) {
        return new ScreenQuote(code, "股票" + code, "行业", 10.0, 1.0, 2.0, 3.0, pe, 1.0, totalCapYi, AS_OF);
    }

    private static ScreenFinance finance(String code, String reportDate, Double roe, Double ocfPerShare) {
        return new ScreenFinance(
                code, reportDate, 1.0, 1e9, 1e8, roe, null, null, null, ocfPerShare, "2026-01-01");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> candidates(ToolResult result) {
        return (List<Map<String, Object>>) result.data().get("candidates");
    }

    private static List<String> codes(ToolResult result) {
        return candidates(result).stream().map(item -> (String) item.get("code")).toList();
    }

    @SuppressWarnings("unchecked")
    private static List<String> notes(ToolResult result) {
        return (List<String>) result.data().get("notes");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> universe(ToolResult result) {
        return (Map<String, Object>) result.data().get("universe");
    }
}
