package com.investmentresearch.data.adapter.finance;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 三大报表字段定义与解析，逐行对应 {@code stock/finance_fields.py}。 */
final class FinanceStatements {
    private FinanceStatements() {}

    record Spec(
            String key,
            String table,
            String symbol,
            Map<String, List<String>> fields,
            List<String> required) {}

    static final Map<String, List<String>> BALANCE_FIELDS =
            aliases(
                    // 银行以"现金及存放中央银行款项"代替"货币资金"
                    "cash", "货币资金|现金及存放中央银行款项",
                    "trading_fin_assets", "交易性金融资产",
                    "notes_receivable", "应收票据",
                    "accounts_receivable", "应收账款",
                    "receivables_financing", "应收款项融资",
                    "prepayments", "预付款项",
                    "other_receivables", "其他应收款(合计)|其他应收款",
                    "inventory", "存货",
                    "current_assets_total", "流动资产合计",
                    "long_term_equity_invest", "长期股权投资",
                    "investment_property", "投资性房地产",
                    "fixed_assets", "固定资产净额|固定资产净值",
                    "construction_in_progress", "在建工程合计|在建工程",
                    "right_of_use_assets", "使用权资产",
                    "intangible_assets", "无形资产",
                    "development_expenditure", "开发支出",
                    "goodwill", "商誉",
                    "deferred_tax_assets", "递延所得税资产",
                    "other_non_current_assets", "其他非流动资产",
                    "non_current_assets_total", "非流动资产合计",
                    "total_assets", "资产总计",
                    "short_term_borrowings", "短期借款",
                    "notes_payable", "应付票据",
                    "accounts_payable", "应付账款",
                    "advances_from_customers", "预收款项",
                    "contract_liabilities", "合同负债",
                    "employee_benefits_payable", "应付职工薪酬",
                    "taxes_payable", "应交税费",
                    "other_payables", "其他应付款合计|其他应付款",
                    "current_liabilities_total", "流动负债合计",
                    "long_term_borrowings", "长期借款",
                    "bonds_payable", "应付债券",
                    "lease_liabilities", "租赁负债",
                    "current_portion_lt_debt", "一年内到期的非流动负债",
                    "non_current_liabilities_total", "非流动负债合计",
                    "total_liabilities", "负债合计",
                    "paid_in_capital", "实收资本(或股本)|股本",
                    "capital_reserve", "资本公积",
                    "treasury_stock", "减:库存股",
                    "surplus_reserve", "盈余公积",
                    "retained_earnings", "未分配利润",
                    "equity_parent", "归属于母公司股东权益合计|归属于母公司股东的权益",
                    "minority_interest", "少数股东权益",
                    "total_equity", "所有者权益(或股东权益)合计",
                    "total_liabilities_and_equity", "负债和所有者权益(或股东权益)总计|负债及股东权益总计");

    static final Map<String, List<String>> INCOME_FIELDS =
            aliases(
                    // 银行、保险没有"营业总收入"，直接以"营业收入"作为总口径
                    "total_revenue", "营业总收入|营业收入",
                    "revenue", "营业收入|营业总收入",
                    "total_cost", "营业总成本",
                    "cost_of_revenue", "营业成本",
                    "taxes_and_surcharges", "营业税金及附加",
                    "rd_expense", "研发费用",
                    "selling_expense", "销售费用",
                    "admin_expense", "管理费用|业务及管理费用",
                    "finance_expense", "财务费用",
                    "interest_expense", "利息费用",
                    "investment_income", "投资收益",
                    "fair_value_change", "公允价值变动收益",
                    "other_income", "其他收益",
                    "asset_impairment", "资产减值损失",
                    "credit_impairment", "信用减值损失",
                    "asset_disposal_income", "资产处置收益",
                    "operating_profit", "营业利润",
                    "non_operating_income", "营业外收入|加:营业外收入",
                    "non_operating_expense", "营业外支出|减:营业外支出",
                    "total_profit", "利润总额",
                    "income_tax", "所得税费用|减:所得税|减:所得税费用",
                    "net_profit", "净利润",
                    "net_profit_attributable", "归属于母公司所有者的净利润|归属于母公司的净利润",
                    // 银行、保险的利润表用"少数股东权益"表示少数股东损益
                    "minority_profit", "少数股东损益|少数股东权益",
                    "basic_eps", "基本每股收益",
                    "diluted_eps", "稀释每股收益");

    static final Map<String, List<String>> CASHFLOW_FIELDS =
            aliases(
                    "cash_from_sales", "销售商品、提供劳务收到的现金",
                    "tax_refund", "收到的税费返还",
                    "operating_cash_inflow", "经营活动现金流入小计",
                    "cash_paid_for_goods", "购买商品、接受劳务支付的现金",
                    "cash_paid_to_employees", "支付给职工以及为职工支付的现金",
                    "taxes_paid", "支付的各项税费",
                    "operating_cash_outflow", "经营活动现金流出小计",
                    "operating_cash_flow", "经营活动产生的现金流量净额",
                    "investment_cash_inflow", "投资活动现金流入小计",
                    "capex", "购建固定资产、无形资产和其他长期资产所支付的现金|购建固定资产、无形资产和其他长期资产支付的现金",
                    "investment_cash_outflow", "投资活动现金流出小计",
                    "investment_cash_flow", "投资活动产生的现金流量净额",
                    "financing_cash_inflow", "筹资活动现金流入小计",
                    "financing_cash_outflow", "筹资活动现金流出小计",
                    "financing_cash_flow", "筹资活动产生的现金流量净额",
                    "fx_effect", "汇率变动对现金及现金等价物的影响",
                    "net_cash_increase", "现金及现金等价物净增加额",
                    "beginning_cash", "期初现金及现金等价物余额",
                    "ending_cash", "期末现金及现金等价物余额");

    static final Spec BALANCE =
            new Spec(
                    "balance",
                    "fin_balance",
                    "资产负债表",
                    BALANCE_FIELDS,
                    List.of("total_assets", "total_liabilities"));
    static final Spec INCOME =
            new Spec(
                    "income",
                    "fin_income",
                    "利润表",
                    INCOME_FIELDS,
                    List.of("total_revenue", "net_profit"));
    static final Spec CASHFLOW =
            new Spec(
                    "cashflow",
                    "fin_cashflow",
                    "现金流量表",
                    CASHFLOW_FIELDS,
                    List.of("operating_cash_flow"));
    static final List<Spec> STATEMENTS = List.of(BALANCE, INCOME, CASHFLOW);

    static final Map<String, String> META_COLUMNS = metaColumns();

    /** 收入类明细排除每股收益，它不是"元"单位。 */
    static final List<String> INCOME_DETAIL_FIELDS =
            INCOME_FIELDS.keySet().stream()
                    .filter(field -> !field.equals("basic_eps") && !field.equals("diluted_eps"))
                    .toList();
    static final List<String> BALANCE_DETAIL_FIELDS = List.copyOf(BALANCE_FIELDS.keySet());
    static final List<String> CASHFLOW_DETAIL_FIELDS = List.copyOf(CASHFLOW_FIELDS.keySet());

    private static final Set<String> EMPTY_VALUES =
            Set.of("", "-", "--", "－", "—", "nan", "none", "null", "不适用", "无");

    /**
     * 把新浪的宽表 JSON 转成统一字段的行列表。{@code data} 已是 {@code
     * payload.result.data}；核心字段缺失时抛 {@link FinanceException}，避免整列静默变空。
     */
    static List<Map<String, Object>> normalize(
            JsonNode data, Spec spec, String code, String fetchedAt) {
        JsonNode dates = data.path("report_date");
        if (!dates.isArray() || dates.isEmpty()) {
            throw new FinanceException(code + " 的" + spec.symbol() + "为空");
        }
        JsonNode reports = data.path("report_list");
        Set<String> columns = new HashSet<>(META_COLUMNS.values());
        List<String> missing = new ArrayList<>();
        // 列校验与 akshare 一致：先确认数据源仍提供核心字段
        for (JsonNode date : dates) {
            JsonNode report = reports.path(date.path("date_value").asText());
            if (report.isMissingNode()) {
                continue;
            }
            for (JsonNode item : report.path("data")) {
                columns.add(item.path("item_title").asText(""));
            }
        }
        for (String field : spec.required()) {
            if (spec.fields().get(field).stream().noneMatch(columns::contains)) {
                missing.add(field);
            }
        }
        if (!missing.isEmpty()) {
            throw new FinanceException(
                    spec.symbol() + "缺少字段：" + String.join(", ", missing) + "（数据源可能已改版）");
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonNode date : dates) {
            String rawDate = date.path("date_value").asText("");
            String reportDate = parseReportDate(rawDate);
            if (reportDate == null) {
                continue;
            }
            JsonNode report = reports.path(rawDate);
            Map<String, JsonNode> values = new LinkedHashMap<>();
            // akshare 对重复列名保留第一列
            for (JsonNode item : report.path("data")) {
                values.putIfAbsent(item.path("item_title").asText(""), item.path("item_value"));
            }

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("code", code);
            row.put("report_date", reportDate);
            for (Map.Entry<String, List<String>> field : spec.fields().entrySet()) {
                row.put(field.getKey(), firstAmount(values, field.getValue()));
            }
            // 银行报表没有"所有者权益合计"，用会计恒等式补出来
            if (spec.key().equals("balance") && row.get("total_equity") == null) {
                Double assets = (Double) row.get("total_assets");
                Double liabilities = (Double) row.get("total_liabilities");
                if (assets != null && liabilities != null) {
                    row.put("total_equity", assets - liabilities);
                }
            }
            row.put("ann_date", parseReportDate(text(values.get(META_COLUMNS.get("ann_date")))));
            row.put("currency", text(values.get(META_COLUMNS.get("currency"))));
            row.put("audited", text(values.get(META_COLUMNS.get("audited"))));
            row.put("statement_type", text(values.get(META_COLUMNS.get("statement_type"))));
            row.put("source", text(values.get(META_COLUMNS.get("source"))));
            row.put("fetched_at", fetchedAt);
            rows.add(row);
        }
        return rows;
    }

    private static Double firstAmount(Map<String, JsonNode> values, List<String> aliases) {
        for (String alias : aliases) {
            Double value = parseAmount(values.get(alias));
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /** {@code finance_fields.py:parse_amount}：千分位、括号负数、空占位符都兼容。 */
    static Double parseAmount(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull() || value.isBoolean()) {
            return null;
        }
        if (value.isNumber()) {
            double number = value.asDouble();
            return Double.isNaN(number) || Double.isInfinite(number) ? null : number;
        }
        String text = value.asText("").strip().replace(",", "").replace("，", "");
        if (EMPTY_VALUES.contains(text.toLowerCase())) {
            return null;
        }
        boolean negative = text.startsWith("(") && text.endsWith(")");
        if (negative) {
            text = text.substring(1, text.length() - 1).strip();
        }
        try {
            double number = Double.parseDouble(text);
            return negative ? -number : number;
        } catch (NumberFormatException error) {
            return null;
        }
    }

    /** {@code finance_fields.py:parse_report_date}：20260630 → 2026-06-30。 */
    static String parseReportDate(String value) {
        if (value == null) {
            return null;
        }
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            if (Character.isDigit(value.charAt(i))) {
                digits.append(value.charAt(i));
            }
        }
        if (digits.length() != 8) {
            return null;
        }
        String year = digits.substring(0, 4);
        String month = digits.substring(4, 6);
        String day = digits.substring(6);
        if (year.compareTo("1990") < 0
                || year.compareTo("2100") > 0
                || month.compareTo("01") < 0
                || month.compareTo("12") > 0
                || day.compareTo("01") < 0
                || day.compareTo("31") > 0) {
            return null;
        }
        return year + "-" + month + "-" + day;
    }

    /** {@code finance_service.py:_text}：去空白，空串视为缺失。 */
    static String text(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) {
            return null;
        }
        String text = value.asText("").strip();
        return text.isEmpty() ? null : text;
    }

    private static Map<String, String> metaColumns() {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("ann_date", "公告日期");
        out.put("currency", "币种");
        out.put("audited", "是否审计");
        out.put("statement_type", "类型");
        out.put("source", "数据源");
        return out;
    }

    private static Map<String, List<String>> aliases(String... pairs) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            out.put(pairs[i], List.of(pairs[i + 1].split("\\|")));
        }
        return out;
    }
}
