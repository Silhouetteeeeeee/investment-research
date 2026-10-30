package com.investmentresearch.data.adapter.finance;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 财务派生指标，逐行对应 {@code stock/finance_metrics.py}。 */
final class FinanceMetrics {
    private FinanceMetrics() {}

    static List<Map<String, Object>> build(List<Map<String, Object>> rows) {
        List<Map<String, Object>> ordered = new ArrayList<>(rows);
        ordered.sort(Comparator.comparing(row -> (String) row.get("report_date")));
        Map<String, Map<String, Object>> byDate = new LinkedHashMap<>();
        for (Map<String, Object> row : ordered) {
            byDate.put((String) row.get("report_date"), row);
        }
        Map<String, Map<String, Double>> singleQuarter = singleQuarterValues(ordered, byDate);

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : ordered) {
            result.add(buildRow(row, byDate, singleQuarter));
        }
        return result;
    }

    private static Map<String, Object> buildRow(
            Map<String, Object> current,
            Map<String, Map<String, Object>> byDate,
            Map<String, Map<String, Double>> singleQuarter) {
        String reportDate = (String) current.get("report_date");
        int elapsed = elapsedDays(reportDate);

        Double revenue = pick(current, "revenue", "total_revenue");
        Double totalRevenue = pick(current, "total_revenue", "revenue");
        Double cost = num(current, "cost_of_revenue");
        Double netProfit = num(current, "net_profit");
        Double netProfitAttr = pick(current, "net_profit_attributable", "net_profit");
        Double totalAssets = num(current, "total_assets");
        Double totalEquity = pick(current, "equity_parent", "total_equity");

        Double sqRevenue = singleQuarter.get(reportDate).get("revenue");
        Double sqProfit = singleQuarter.get(reportDate).get("net_profit");
        String lastYear = sameQuarterLastYear(reportDate);
        Map<String, Double> lastYearQuarter = singleQuarter.get(lastYear);
        Double sqRevenueLastYear = lastYearQuarter == null ? null : lastYearQuarter.get("revenue");
        Double sqProfitLastYear =
                lastYearQuarter == null ? null : lastYearQuarter.get("net_profit");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("report_date", reportDate);
        out.put("revenue", totalRevenue);
        out.put(
                "revenue_yoy",
                growth(totalRevenue, value(byDate, lastYear, "total_revenue")));
        out.put("revenue_sq", sqRevenue);
        out.put("revenue_sq_yoy", growth(sqRevenue, sqRevenueLastYear));
        out.put("net_profit", netProfit);
        out.put("net_profit_yoy", growth(netProfit, value(byDate, lastYear, "net_profit")));
        out.put("net_profit_sq", sqProfit);
        out.put("net_profit_sq_yoy", growth(sqProfit, sqProfitLastYear));
        out.put("gross_margin", ratio(subtract(revenue, cost), revenue, 100.0));
        out.put("net_margin", ratio(netProfit, totalRevenue, 100.0));
        out.put("rd_ratio", ratio(num(current, "rd_expense"), totalRevenue, 100.0));
        out.put(
                "roe",
                annualized(
                        netProfitAttr,
                        average(
                                totalEquity,
                                value(
                                        byDate,
                                        previousYearEnd(reportDate),
                                        "equity_parent",
                                        "total_equity")),
                        elapsed));
        out.put(
                "roa",
                annualized(
                        netProfit,
                        average(totalAssets, value(byDate, previousYearEnd(reportDate), "total_assets")),
                        elapsed));
        out.put("debt_ratio", ratio(num(current, "total_liabilities"), totalAssets, 100.0));
        out.put(
                "current_ratio",
                ratio(num(current, "current_assets_total"), num(current, "current_liabilities_total")));
        out.put(
                "quick_ratio",
                ratio(
                        subtract(num(current, "current_assets_total"), num(current, "inventory")),
                        num(current, "current_liabilities_total")));
        out.put(
                "ar_turnover_days",
                turnoverDays(
                        num(current, "accounts_receivable"),
                        value(byDate, previousYearEnd(reportDate), "accounts_receivable"),
                        revenue,
                        elapsed));
        out.put(
                "inventory_turnover_days",
                turnoverDays(
                        num(current, "inventory"),
                        value(byDate, previousYearEnd(reportDate), "inventory"),
                        cost,
                        elapsed));
        out.put("ocf_to_net_profit", ratio(num(current, "operating_cash_flow"), netProfit));
        out.put("cash_to_revenue", ratio(num(current, "cash_from_sales"), totalRevenue, 100.0));
        out.put("operating_cash_flow", num(current, "operating_cash_flow"));
        out.put("capex", num(current, "capex"));
        out.put(
                "free_cash_flow", subtract(num(current, "operating_cash_flow"), num(current, "capex")));
        out.put("net_debt", netDebt(current));
        return out;
    }

    /** 累计值差分成本单季值；缺上一期时返回 null，宁可缺值也不编造。 */
    private static Map<String, Map<String, Double>> singleQuarterValues(
            List<Map<String, Object>> ordered,
            Map<String, Map<String, Object>> byDate) {
        Map<String, Map<String, Double>> values = new LinkedHashMap<>();
        for (Map<String, Object> row : ordered) {
            String reportDate = (String) row.get("report_date");
            int quarter = quarter(reportDate);
            Map<String, Object> previous = byDate.get(previousQuarter(reportDate));
            Map<String, Double> current = new LinkedHashMap<>();
            for (String field : List.of("revenue", "total_revenue", "net_profit")) {
                Double value = num(row, field);
                if (value == null) {
                    current.put(field, null);
                } else if (quarter == 1) {
                    current.put(field, value);
                } else if (previous == null || num(previous, field) == null) {
                    current.put(field, null);
                } else {
                    current.put(field, value - num(previous, field));
                }
            }
            values.put(reportDate, current);
        }
        // 收入优先用"营业收入"，缺失时退回"营业总收入"
        for (Map<String, Object> row : ordered) {
            String reportDate = (String) row.get("report_date");
            Map<String, Double> current = values.get(reportDate);
            if (current.get("revenue") == null) {
                current.put("revenue", current.get("total_revenue"));
            }
        }
        return values;
    }

    private static int quarter(String reportDate) {
        return (LocalDate.parse(reportDate).getMonthValue() - 1) / 3 + 1;
    }

    private static String previousQuarter(String reportDate) {
        LocalDate current = LocalDate.parse(reportDate);
        int year = current.getYear();
        return switch (quarter(reportDate)) {
            case 1 -> year - 1 + "-12-31";
            case 2 -> year + "-03-31";
            case 3 -> year + "-06-30";
            default -> year + "-09-30";
        };
    }

    private static String sameQuarterLastYear(String reportDate) {
        LocalDate current = LocalDate.parse(reportDate);
        return "%d-%02d-%02d"
                .formatted(current.getYear() - 1, current.getMonthValue(), current.getDayOfMonth());
    }

    private static String previousYearEnd(String reportDate) {
        return (LocalDate.parse(reportDate).getYear() - 1) + "-12-31";
    }

    /** 报告期从年初到期末经过的天数，用于周转率与年化。 */
    private static int elapsedDays(String reportDate) {
        LocalDate current = LocalDate.parse(reportDate);
        return (int) ChronoUnit.DAYS.between(LocalDate.of(current.getYear(), 1, 1), current) + 1;
    }

    private static Double pick(Map<String, Object> row, String... keys) {
        for (String key : keys) {
            Double value = num(row, key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static Double value(
            Map<String, Map<String, Object>> byDate, String reportDate, String... keys) {
        Map<String, Object> row = byDate.get(reportDate);
        return row == null ? null : pick(row, keys);
    }

    private static Double num(Map<String, Object> row, String key) {
        Object value = row == null ? null : row.get(key);
        return value instanceof Number number ? number.doubleValue() : null;
    }

    private static Double subtract(Double left, Double right) {
        return left == null || right == null ? null : left - right;
    }

    private static Double average(Double left, Double right) {
        if (left == null) {
            return null;
        }
        return right == null ? left : (left + right) / 2;
    }

    private static Double ratio(Double numerator, Double denominator) {
        return ratio(numerator, denominator, 1.0);
    }

    private static Double ratio(Double numerator, Double denominator, double scale) {
        if (numerator == null || denominator == null || denominator == 0) {
            return null;
        }
        return numerator / denominator * scale;
    }

    /** 把报告期利润年化后除以平均资产/权益，得到可比口径的回报率。 */
    private static Double annualized(Double profit, Double base, int elapsed) {
        if (profit == null || base == null || base == 0 || elapsed <= 0) {
            return null;
        }
        return profit * (365.0 / elapsed) / base * 100.0;
    }

    private static Double growth(Double current, Double previous) {
        if (current == null || previous == null || previous == 0) {
            return null;
        }
        return (current - previous) / Math.abs(previous) * 100.0;
    }

    /** 周转天数 = 平均余额 / 报告期收入 × 报告期天数。 */
    private static Double turnoverDays(
            Double ending, Double beginning, Double flow, int elapsed) {
        if (ending == null || flow == null || flow == 0) {
            return null;
        }
        Double average = average(ending, beginning);
        return average == null ? null : average / flow * elapsed;
    }

    /** 有息负债减去现金及交易性金融资产；任一主项缺失时不计算。 */
    private static Double netDebt(Map<String, Object> row) {
        Double cash = num(row, "cash");
        if (cash == null) {
            return null;
        }
        List<Double> debtItems =
                java.util.Arrays.asList(
                        num(row, "short_term_borrowings"),
                        num(row, "current_portion_lt_debt"),
                        num(row, "long_term_borrowings"),
                        num(row, "bonds_payable"),
                        num(row, "lease_liabilities"));
        if (debtItems.stream().allMatch(java.util.Objects::isNull)) {
            return null;
        }
        double debt = debtItems.stream().filter(java.util.Objects::nonNull).mapToDouble(Double::doubleValue).sum();
        Double trading = num(row, "trading_fin_assets");
        double liquid = cash + (trading == null ? 0.0 : trading);
        return debt - liquid;
    }
}
