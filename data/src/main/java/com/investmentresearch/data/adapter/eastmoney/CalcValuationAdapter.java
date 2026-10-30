package com.investmentresearch.data.adapter.eastmoney;

import com.investmentresearch.core.domain.Source;
import com.investmentresearch.core.domain.ToolResult;
import com.investmentresearch.core.port.ValuationCheckPort;
import com.investmentresearch.core.valuation.ValuationMath;
import com.investmentresearch.data.adapter.finance.FinanceAdapter;
import com.investmentresearch.data.persistence.StockMapper;
import com.investmentresearch.data.persistence.ValHistoryEntity;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Repository;

/** calc_valuation 的 check 模式，照抄 {@code assistant/tools.py:_valuation_check}。 */
@Repository
public class CalcValuationAdapter implements ValuationCheckPort {
    private final ValuationSupport support;
    private final FinanceAdapter finance;
    private final StockMapper stockMapper;

    public CalcValuationAdapter(
            ValuationSupport support, FinanceAdapter finance, StockMapper stockMapper) {
        this.support = support;
        this.finance = finance;
        this.stockMapper = stockMapper;
    }

    @Override
    public ToolResult check(String rawCode) {
        String code = EastmoneySupport.normalize(rawCode);
        if (code.isEmpty()) {
            return ToolResult.failure(rawCode, "请输入股票代码");
        }
        try {
            return build(code);
        } catch (RuntimeException error) {
            return ToolResult.failure(code, error.getMessage());
        }
    }

    private ToolResult build(String code) {
        List<ValHistoryEntity> history = support.ensure(code).history();
        if (history.isEmpty()) {
            return ToolResult.failure(code, code + "：没有估值数据，无法校验");
        }
        ValHistoryEntity latest = history.get(history.size() - 1);
        Double reportedPe = latest.getPeTtm();
        Double reportedPb = latest.getPbMrq();
        Double price = latest.getClose();
        Double marketCap = latest.getTotalMv();
        String asOf = latest.getTradeDate() == null ? "" : latest.getTradeDate();
        if (marketCap == null) {
            return ToolResult.failure(code, code + "：估值数据缺少市值，无法校验");
        }

        List<Map<String, Object>> rows = new ArrayList<>(finance.freshHistory(code));
        rows.sort(
                (a, b) ->
                        String.valueOf(a.get("report_date"))
                                .compareTo(String.valueOf(b.get("report_date"))));
        if (rows.isEmpty()) {
            return ToolResult.failure(code, code + "：没有可用的财报数据");
        }
        Map<String, Object> latestRow = rows.get(rows.size() - 1);
        String period = String.valueOf(latestRow.get("report_date"));
        boolean financial = latestRow.get("cost_of_revenue") == null;

        Double ttmProfit = ttm(rows, List.of("net_profit_attributable", "net_profit"));
        Double ttmOcf = ttm(rows, List.of("operating_cash_flow"));
        Double ttmCapex = ttm(rows, List.of("capex"));
        Double ttmFcf = ttmOcf != null && ttmCapex != null ? ttmOcf - ttmCapex : null;
        Double equity = field(latestRow, List.of("equity_parent", "total_equity"));

        Double peImplied = ttmProfit != null && ttmProfit > 0 ? marketCap / ttmProfit : null;
        Double pbImplied = equity != null && equity > 0 ? marketCap / equity : null;
        Map<String, Object> peCheck = ValuationMath.compare("PE(TTM)", reportedPe, peImplied);
        Map<String, Object> pbCheck = ValuationMath.compare("PB(MRQ)", reportedPb, pbImplied);
        Double yieldValue = financial ? null : ValuationMath.fcfYield(ttmFcf, marketCap);

        List<String> warnings = new ArrayList<>();
        for (Map<String, Object> checkItem : List.of(peCheck, pbCheck)) {
            String status = (String) checkItem.get("status");
            Double diff = (Double) checkItem.get("diff_pct");
            if ("fail".equals(status)) {
                warnings.add(
                        checkItem.get("name")
                                + " 校验差异 "
                                + String.format(Locale.ROOT, "%+.2f", diff)
                                + "%，可能是指标口径或财报未同步，引用前先核对");
            } else if ("warn".equals(status)) {
                warnings.add(
                        checkItem.get("name")
                                + " 校验差异 "
                                + String.format(Locale.ROOT, "%+.2f", diff)
                                + "%，注意 TTM/MRQ 口径");
            }
        }
        if (ttmProfit != null && ttmProfit <= 0) {
            warnings.add("TTM 归母净利润为负，PE 不适用");
        }
        if (financial) {
            warnings.add("金融机构：自由现金流收益率口径不可比，未计算");
        } else if (ttmFcf == null) {
            warnings.add("缺少经营现金流或资本开支，未计算自由现金流收益率");
        }
        Double minority = number(latestRow.get("minority_interest"));
        if (minority != null && minority != 0) {
            warnings.add(
                    "PB 按归母净资产校验；少数股东权益 "
                            + DatacenterSupport.yi(minority)
                            + " 未计入");
        }

        Map<String, Object> valuation = new LinkedHashMap<>();
        valuation.put("pe_ttm", reportedPe);
        valuation.put("pb_mrq", reportedPb);
        valuation.put("as_of", asOf);

        Map<String, Object> financials = new LinkedHashMap<>();
        financials.put("period", period);
        financials.put("ttm_net_profit_yi", DatacenterSupport.yi(ttmProfit));
        financials.put("equity_parent_yi", DatacenterSupport.yi(equity));
        financials.put("ttm_fcf_yi", DatacenterSupport.yi(ttmFcf));
        financials.put("is_financial", financial);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("mode", "check");
        data.put("price", DatacenterSupport.round(price, 2));
        data.put("market_cap_yi", DatacenterSupport.yi(marketCap));
        data.put("valuation", valuation);
        data.put("financials", financials);
        data.put("checks", List.of(peCheck, pbCheck));
        data.put(
                "fcf_yield_pct",
                DatacenterSupport.round(yieldValue, 2));
        data.put("warnings", warnings);
        data.put(
                "notes",
                List.of(
                        "PE/PB 用东财估值与新浪财报两套数据交叉校验；"
                                + "差异可能来自 TTM/MRQ 口径或财报同步时点，不代表数据错误。"));

        String name = stockMapper.selectNameByCode(code);
        if (name == null || name.isEmpty()) {
            name = code;
        }
        List<Source> sources = new ArrayList<>();
        if (!asOf.isEmpty()) {
            sources.add(
                    new Source(
                            "valuation:" + code + ":" + asOf,
                            "估值",
                            name + " " + asOf + " 估值",
                            asOf,
                            latest.getFetchedAt() == null ? "" : latest.getFetchedAt()));
        }
        Object financeStamp = latestRow.get("fetched_at");
        sources.add(
                new Source(
                        "finance:" + code + ":" + period,
                        "财报",
                        name + " " + period + " 财务快照",
                        period,
                        financeStamp == null ? "" : String.valueOf(financeStamp)));
        return ToolResult.ok(code, asOf.isEmpty() ? period : asOf, sources, data);
    }

    /** 最新报告期的 TTM：本期累计 + 上年年报 − 上年同期累计。 */
    private static Double ttm(List<Map<String, Object>> rows, List<String> fields) {
        Map<String, Object> latest = rows.get(rows.size() - 1);
        String period = String.valueOf(latest.get("report_date"));
        int year = Integer.parseInt(period.substring(0, 4));
        Map<String, Map<String, Object>> byDate = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            byDate.put(String.valueOf(row.get("report_date")), row);
        }
        Double current = field(latest, fields);
        Double annual =
                field(byDate.get(Integer.toString(year - 1) + "-12-31"), fields);
        Double sameQuarter =
                field(byDate.get(Integer.toString(year - 1) + period.substring(4)), fields);
        if (current == null || annual == null || sameQuarter == null) {
            return null;
        }
        return current + annual - sameQuarter;
    }

    private static Double field(Map<String, Object> row, List<String> fields) {
        if (row == null) {
            return null;
        }
        for (String name : fields) {
            Double value = number(row.get(name));
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static Double number(Object value) {
        return value instanceof Number number ? number.doubleValue() : null;
    }

}
