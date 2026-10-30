package com.investmentresearch.data.adapter.finance;

import com.investmentresearch.core.domain.Source;
import com.investmentresearch.core.domain.ToolResult;
import com.investmentresearch.core.port.FinancialHistoryPort;
import com.investmentresearch.data.persistence.StockMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Repository;

/** 年度财务序列，照抄 {@code stock/finance_history.py} 与 {@code assistant/tools.py:_financial_history}。 */
@Repository
public class FinancialHistoryAdapter implements FinancialHistoryPort {
    private static final String SOURCE_LABEL = "财报·年度序列";

    private final FinanceAdapter finance;
    private final StockMapper stockMapper;

    public FinancialHistoryAdapter(FinanceAdapter finance, StockMapper stockMapper) {
        this.finance = finance;
        this.stockMapper = stockMapper;
    }

    @Override
    public ToolResult financialHistory(String rawCode, int years) {
        String code = FinanceSupport.normalize(rawCode);
        if (code.isEmpty()) {
            return ToolResult.failure(rawCode, "请输入股票代码");
        }
        try {
            Map<String, Object> data =
                    historyFromRows(finance.freshHistory(code), Math.min(Math.max(years, 1), 20));
            if (!Boolean.TRUE.equals(data.get("available"))) {
                return ToolResult.failure(code, code + "：没有可用的年报序列");
            }
            String name = stockMapper.selectNameByCode(code);
            String span = String.valueOf(data.get("span"));
            String asOf = String.valueOf(data.get("as_of"));
            Source source =
                    new Source(
                            "finance_history:" + code + ":" + span,
                            SOURCE_LABEL,
                            (name == null || name.isEmpty() ? code : name) + " " + span + " 年度财务",
                            asOf,
                            data.get("fetched_at") == null ? "" : String.valueOf(data.get("fetched_at")));

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("code", code);
            out.put("name", name);
            out.put("span", span);
            out.put("as_of", asOf);
            out.put("years", data.get("years"));
            out.put("notes", data.get("notes"));
            return ToolResult.ok(code, asOf, List.of(source), out);
        } catch (RuntimeException error) {
            return ToolResult.failure(code, error.getMessage());
        }
    }

    /** {@code stock/finance_history.py:history_from_rows}。 */
    static Map<String, Object> historyFromRows(List<Map<String, Object>> rows, int years) {
        List<Map<String, Object>> annual = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Object reportDate = row.get("report_date");
            if (reportDate instanceof String text && text.endsWith("-12-31")) {
                annual.add(row);
            }
        }
        annual.sort((a, b) -> ((String) a.get("report_date")).compareTo((String) b.get("report_date")));

        Map<String, Object> data = new LinkedHashMap<>();
        if (annual.isEmpty()) {
            data.put("available", false);
            data.put("as_of", null);
            data.put("fetched_at", null);
            data.put("span", null);
            data.put("years", List.of());
            data.put("notes", List.of("没有年报数据"));
            return data;
        }

        List<Map<String, Object>> selected =
                annual.subList(Math.max(0, annual.size() - years), annual.size());
        String firstYear = ((String) selected.get(0).get("report_date")).substring(0, 4);
        String lastYear =
                ((String) selected.get(selected.size() - 1).get("report_date")).substring(0, 4);
        List<String> notes = new ArrayList<>();
        if (selected.size() < years) {
            notes.add("只有 " + selected.size() + " 年年报（计划回看 " + years + " 年）");
        }
        List<String> missing = missingYears(selected);
        if (!missing.isEmpty()) {
            notes.add("缺少年报：" + String.join("、", missing));
        }

        String fetchedAt = null;
        for (Map<String, Object> row : selected) {
            Object stamp = row.get("fetched_at");
            if (stamp instanceof String text
                    && !text.isEmpty()
                    && (fetchedAt == null || text.compareTo(fetchedAt) > 0)) {
                fetchedAt = text;
            }
        }
        List<Map<String, Object>> yearRows = new ArrayList<>();
        for (Map<String, Object> row : selected) {
            yearRows.add(yearRow(row));
        }

        data.put("available", true);
        data.put("as_of", selected.get(selected.size() - 1).get("report_date"));
        data.put("fetched_at", fetchedAt);
        data.put("span", firstYear + "-" + lastYear);
        data.put("years", yearRows);
        data.put("notes", notes);
        return data;
    }

    private static List<String> missingYears(List<Map<String, Object>> rows) {
        Set<String> present = new LinkedHashSet<>();
        for (Map<String, Object> row : rows) {
            present.add(((String) row.get("report_date")).substring(0, 4));
        }
        int start = Integer.parseInt(((String) rows.get(0).get("report_date")).substring(0, 4));
        int end =
                Integer.parseInt(
                        ((String) rows.get(rows.size() - 1).get("report_date")).substring(0, 4));
        List<String> missing = new ArrayList<>();
        for (int year = start; year <= end; year++) {
            if (!present.contains(String.valueOf(year))) {
                missing.add(String.valueOf(year));
            }
        }
        return missing;
    }

    /** {@code stock/finance_history.py:_year_row}。 */
    private static Map<String, Object> yearRow(Map<String, Object> row) {
        Double revenue = num(row, "revenue");
        // 金融机构没有"营业成本"，毛利率与费用率口径都不可比，整组留空
        boolean financial = row.get("cost_of_revenue") == null && row.get("gross_margin") == null;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("period", row.get("report_date"));
        out.put("revenue_yi", FinanceSupport.yi(revenue));
        out.put("revenue_yoy", FinanceSupport.round(num(row, "revenue_yoy"), 1));
        out.put("net_profit_yi", FinanceSupport.yi(num(row, "net_profit")));
        out.put("net_profit_yoy", FinanceSupport.round(num(row, "net_profit_yoy"), 1));
        out.put("basic_eps", FinanceSupport.round(num(row, "basic_eps"), 3));
        out.put(
                "gross_margin",
                financial ? null : FinanceSupport.round(num(row, "gross_margin"), 1));
        out.put("net_margin", FinanceSupport.round(num(row, "net_margin"), 1));
        out.put("roe", FinanceSupport.round(num(row, "roe"), 1));
        out.put("roa", FinanceSupport.round(num(row, "roa"), 1));
        out.put(
                "selling_ratio",
                financial ? null : ratio(num(row, "selling_expense"), revenue));
        out.put("admin_ratio", financial ? null : ratio(num(row, "admin_expense"), revenue));
        out.put("rd_ratio", financial ? null : ratio(num(row, "rd_expense"), revenue));
        out.put(
                "ocf_to_net_profit",
                financial ? null : FinanceSupport.round(num(row, "ocf_to_net_profit"), 3));
        out.put("operating_cash_flow_yi", FinanceSupport.yi(num(row, "operating_cash_flow")));
        out.put("capex_yi", FinanceSupport.yi(num(row, "capex")));
        out.put("free_cash_flow_yi", FinanceSupport.yi(num(row, "free_cash_flow")));
        out.put("equity_parent_yi", FinanceSupport.yi(num(row, "equity_parent")));
        out.put("net_debt_yi", FinanceSupport.yi(num(row, "net_debt")));
        out.put(
                "interest_debt_yi",
                financial ? null : FinanceSupport.yi(interestDebt(row)));
        out.put("fixed_assets_yi", FinanceSupport.yi(num(row, "fixed_assets")));
        out.put(
                "construction_in_progress_yi",
                FinanceSupport.yi(num(row, "construction_in_progress")));
        out.put("debt_ratio", FinanceSupport.round(num(row, "debt_ratio"), 1));
        return out;
    }

    /** 有息负债合计；科目全缺时返回 None，不用 0 冒充没有负债。 */
    private static Double interestDebt(Map<String, Object> row) {
        List<String> keys =
                List.of(
                        "short_term_borrowings",
                        "current_portion_lt_debt",
                        "long_term_borrowings",
                        "bonds_payable",
                        "lease_liabilities");
        double sum = 0;
        boolean any = false;
        for (String key : keys) {
            Double value = num(row, key);
            if (value != null) {
                sum += value;
                any = true;
            }
        }
        return any ? sum : null;
    }

    /** 费用率为负多是冲回或数据源符号问题，负收入也没有可比口径，两种都留空。 */
    private static Double ratio(Double part, Double revenue) {
        if (part == null || part < 0 || revenue == null || revenue <= 0) {
            return null;
        }
        return FinanceSupport.round(part / revenue * 100.0, 1);
    }

    private static Double num(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return null;
    }
}
