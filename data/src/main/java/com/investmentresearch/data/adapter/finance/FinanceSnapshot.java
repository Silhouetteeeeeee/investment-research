package com.investmentresearch.data.adapter.finance;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 基本面快照，逐行对应 {@code stock/finance_snapshot.py}（只保留 get_finance 用到的部分）。 */
final class FinanceSnapshot {
    private FinanceSnapshot() {}

    static final int DEFAULT_TREND_PERIODS = 8;
    static final String SOURCE_LABEL = "新浪财经 stock_financial_report_sina";

    private static final List<String> DETAIL_FIELDS =
            List.of(
                    "basic_eps",
                    "diluted_eps",
                    "equity_parent",
                    "minority_interest",
                    "total_equity",
                    "total_assets",
                    "total_liabilities",
                    "fixed_assets",
                    "construction_in_progress",
                    "intangible_assets",
                    "long_term_equity_invest",
                    "notes_payable",
                    "accounts_payable",
                    "advances_from_customers",
                    "contract_liabilities",
                    "investment_income",
                    "fair_value_change",
                    "asset_impairment",
                    "credit_impairment",
                    "treasury_stock",
                    "rd_expense",
                    "capex");

    private static final List<String> WORKING_CAPITAL_YOY_FIELDS =
            List.of(
                    "notes_receivable",
                    "prepayments",
                    "other_receivables",
                    "accounts_payable",
                    "contract_liabilities",
                    "development_expenditure");

    /** 由合并后的财报行生成快照（纯函数，不碰数据库和网络）。 */
    static Map<String, Object> build(List<Map<String, Object>> rows, int periods, String name) {
        List<Map<String, Object>> ordered = new ArrayList<>(rows);
        ordered.sort(Comparator.comparing(row -> (String) row.get("report_date")));
        Map<String, Object> snapshot = new LinkedHashMap<>();
        if (ordered.isEmpty()) {
            snapshot.put("available", false);
            snapshot.put("code", null);
            snapshot.put("name", name);
            snapshot.put("periods", 0);
            snapshot.put("latest", null);
            snapshot.put("metrics", Map.of());
            snapshot.put("trend", List.of());
            snapshot.put("sources", Map.of("statement", SOURCE_LABEL));
            return snapshot;
        }

        Map<String, Object> latest = ordered.get(ordered.size() - 1);
        String reportDate = (String) latest.get("report_date");
        Map<String, Object> lastYear = byDate(ordered, sameQuarterLastYear(reportDate));
        List<Map<String, Object>> trend =
                ordered.subList(ordered.size() - Math.max(1, periods), ordered.size());

        Map<String, Object> latestOut = new LinkedHashMap<>();
        latestOut.put("report_date", reportDate);
        latestOut.put("ann_date", latest.get("ann_date"));
        latestOut.put("audited", latest.get("audited"));
        latestOut.put("fetched_at", latest.get("fetched_at"));

        snapshot.put("available", true);
        snapshot.put("code", latest.get("code"));
        snapshot.put("name", name);
        snapshot.put("periods", ordered.size());
        snapshot.put("latest", latestOut);
        snapshot.put("metrics", metrics(latest, lastYear));
        List<Map<String, Object>> trendOut = new ArrayList<>();
        for (Map<String, Object> row : trend) {
            trendOut.add(trendRow(row));
        }
        snapshot.put("trend", trendOut);
        snapshot.put("sources", Map.of("statement", SOURCE_LABEL));
        return snapshot;
    }

    private static Map<String, Object> metrics(
            Map<String, Object> latest, Map<String, Object> lastYear) {
        Map<String, Object> out = new LinkedHashMap<>();

        Map<String, Object> revenue = new LinkedHashMap<>();
        revenue.put("value", num(latest, "revenue"));
        revenue.put("yoy", num(latest, "revenue_yoy"));
        revenue.put("single_quarter", num(latest, "revenue_sq"));
        revenue.put("single_quarter_yoy", num(latest, "revenue_sq_yoy"));
        out.put("revenue", revenue);

        Map<String, Object> profit = new LinkedHashMap<>();
        profit.put("value", num(latest, "net_profit"));
        profit.put("attributable", num(latest, "net_profit_attributable"));
        profit.put("yoy", num(latest, "net_profit_yoy"));
        profit.put("single_quarter", num(latest, "net_profit_sq"));
        profit.put("single_quarter_yoy", num(latest, "net_profit_sq_yoy"));
        out.put("net_profit", profit);

        out.put("gross_margin", levelMetric(latest, lastYear, "gross_margin"));
        out.put("net_margin", levelMetric(latest, lastYear, "net_margin"));
        out.put("debt_ratio", levelMetric(latest, lastYear, "debt_ratio"));
        out.put("roe", singleValue(latest, "roe"));
        out.put("roa", singleValue(latest, "roa"));
        out.put("current_ratio", singleValue(latest, "current_ratio"));
        out.put("quick_ratio", singleValue(latest, "quick_ratio"));
        out.put("rd_ratio", singleValue(latest, "rd_ratio"));

        Map<String, Object> cash = new LinkedHashMap<>();
        cash.put("operating_cash_flow", num(latest, "operating_cash_flow"));
        cash.put("free_cash_flow", num(latest, "free_cash_flow"));
        // 银行的经营现金流主要由存款变动决定，和净利润没有可比性
        cash.put(
                "ocf_to_net_profit",
                isFinancial(latest) ? null : num(latest, "ocf_to_net_profit"));
        cash.put("net_debt", isFinancial(latest) ? null : num(latest, "net_debt"));
        cash.put("cash_balance", num(latest, "cash"));
        cash.put("short_term_borrowings", num(latest, "short_term_borrowings"));
        out.put("cash", cash);

        out.put("safety", safety(latest, lastYear));
        out.put("detail", detail(latest, lastYear));
        return out;
    }

    /** 银行、保险等金融机构没有营业成本科目，部分指标口径不可比。 */
    private static boolean isFinancial(Map<String, Object> row) {
        return num(row, "cost_of_revenue") == null;
    }

    private static Map<String, Object> safety(
            Map<String, Object> latest, Map<String, Object> lastYear) {
        Map<String, Object> safety = new LinkedHashMap<>();
        Double interestDebt =
                sum(
                        num(latest, "short_term_borrowings"),
                        num(latest, "current_portion_lt_debt"),
                        num(latest, "long_term_borrowings"),
                        num(latest, "bonds_payable"),
                        num(latest, "lease_liabilities"));
        safety.put("interest_debt", interestDebt);
        safety.put(
                "short_term_debt",
                sum(
                        num(latest, "short_term_borrowings"),
                        num(latest, "current_portion_lt_debt")));
        safety.put("interest_coverage", null);
        safety.put("interest_coverage_basis", null);
        safety.put("goodwill", num(latest, "goodwill"));
        safety.put("goodwill_ratio", null);
        safety.put("accounts_receivable", num(latest, "accounts_receivable"));
        safety.put(
                "accounts_receivable_yoy",
                growth(num(latest, "accounts_receivable"), value(lastYear, "accounts_receivable")));
        safety.put("inventory", num(latest, "inventory"));
        safety.put("inventory_yoy", growth(num(latest, "inventory"), value(lastYear, "inventory")));
        safety.put("cash_to_short_term_debt", null);
        if (isFinancial(latest)) {
            Map<String, Object> blank = new LinkedHashMap<>();
            for (String key : safety.keySet()) {
                blank.put(key, null);
            }
            return blank;
        }

        Object[] coverage = interestCoverage(latest);
        safety.put("interest_coverage", coverage[0]);
        safety.put("interest_coverage_basis", coverage[1]);

        Double goodwill = num(latest, "goodwill");
        Double equity = num(latest, "total_equity");
        if (goodwill != null && equity != null && equity > 0) {
            safety.put(
                    "goodwill_ratio",
                    FinanceSupport.round(goodwill / equity * 100.0, 1));
        }

        Double cash = num(latest, "cash");
        Double shortTerm = (Double) safety.get("short_term_debt");
        if (cash != null && shortTerm != null && shortTerm != 0) {
            safety.put(
                    "cash_to_short_term_debt",
                    FinanceSupport.round(cash / shortTerm, 2));
        }
        return safety;
    }

    /** 利息保障倍数：(利润总额 + 利息费用) / 利息费用；没有利息费用时退回财务费用近似。 */
    private static Object[] interestCoverage(Map<String, Object> row) {
        Double profit = num(row, "total_profit");
        Double interest = num(row, "interest_expense");
        String basis = "利息费用";
        if (interest == null) {
            interest = num(row, "finance_expense");
            basis = "财务费用（近似）";
        }
        if (profit == null || interest == null || interest <= 0) {
            return new Object[] {null, null};
        }
        return new Object[] {FinanceSupport.round((profit + interest) / interest, 1), basis};
    }

    private static Map<String, Object> detail(
            Map<String, Object> latest, Map<String, Object> lastYear) {
        Map<String, Object> detail = new LinkedHashMap<>();
        for (String field : DETAIL_FIELDS) {
            detail.put(field, latest.get(field));
        }
        detail.put(
                "fixed_assets_yoy",
                growth(num(latest, "fixed_assets"), value(lastYear, "fixed_assets")));
        detail.put(
                "construction_in_progress_yoy",
                growth(
                        num(latest, "construction_in_progress"),
                        value(lastYear, "construction_in_progress")));
        for (String field : WORKING_CAPITAL_YOY_FIELDS) {
            detail.put(field + "_yoy", growth(num(latest, field), value(lastYear, field)));
        }

        // 完整三表科目按报表分组，工具层统一换算成亿元；这里保留原始单位
        Map<String, Object> balance = new LinkedHashMap<>();
        for (String field : FinanceStatements.BALANCE_DETAIL_FIELDS) {
            balance.put(field, latest.get(field));
        }
        detail.put("balance", balance);
        Map<String, Object> income = new LinkedHashMap<>();
        for (String field : FinanceStatements.INCOME_DETAIL_FIELDS) {
            income.put(field, latest.get(field));
        }
        detail.put("income", income);
        Map<String, Object> cashflow = new LinkedHashMap<>();
        for (String field : FinanceStatements.CASHFLOW_DETAIL_FIELDS) {
            cashflow.put(field, latest.get(field));
        }
        detail.put("cashflow", cashflow);
        return detail;
    }

    private static Map<String, Object> levelMetric(
            Map<String, Object> latest, Map<String, Object> lastYear, String field) {
        Double value = num(latest, field);
        Double previous = value(lastYear, field);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("value", value);
        out.put("change_pp", value == null || previous == null ? null : value - previous);
        return out;
    }

    private static Map<String, Object> singleValue(Map<String, Object> latest, String field) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("value", num(latest, field));
        return out;
    }

    private static Map<String, Object> trendRow(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("report_date", row.get("report_date"));
        out.put("revenue_sq", num(row, "revenue_sq"));
        out.put("revenue_sq_yoy", num(row, "revenue_sq_yoy"));
        out.put("net_profit_sq", num(row, "net_profit_sq"));
        out.put("net_profit_sq_yoy", num(row, "net_profit_sq_yoy"));
        out.put("gross_margin", num(row, "gross_margin"));
        out.put("net_margin", num(row, "net_margin"));
        return out;
    }

    private static Double sum(Double... values) {
        double total = 0;
        boolean present = false;
        for (Double value : values) {
            if (value != null) {
                total += value;
                present = true;
            }
        }
        return present ? total : null;
    }

    private static Double growth(Double current, Double previous) {
        if (current == null || previous == null || previous == 0) {
            return null;
        }
        return (current - previous) / Math.abs(previous) * 100.0;
    }

    private static Double value(Map<String, Object> row, String field) {
        return row == null ? null : num(row, field);
    }

    private static Double num(Map<String, Object> row, String field) {
        Object value = row == null ? null : row.get(field);
        return value instanceof Number number ? number.doubleValue() : null;
    }

    private static Map<String, Object> byDate(
            List<Map<String, Object>> rows, String reportDate) {
        for (Map<String, Object> row : rows) {
            if (Objects.equals(row.get("report_date"), reportDate)) {
                return row;
            }
        }
        return null;
    }

    private static String sameQuarterLastYear(String reportDate) {
        LocalDate current = LocalDate.parse(reportDate);
        return "%d-%02d-%02d"
                .formatted(current.getYear() - 1, current.getMonthValue(), current.getDayOfMonth());
    }
}
