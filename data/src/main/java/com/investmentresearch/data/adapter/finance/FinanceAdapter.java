package com.investmentresearch.data.adapter.finance;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.investmentresearch.core.domain.Source;
import com.investmentresearch.core.domain.ToolResult;
import com.investmentresearch.core.port.FinancePort;
import com.investmentresearch.data.persistence.FinBalanceEntity;
import com.investmentresearch.data.persistence.FinBalanceMapper;
import com.investmentresearch.data.persistence.FinCashflowEntity;
import com.investmentresearch.data.persistence.FinCashflowMapper;
import com.investmentresearch.data.persistence.FinDisclosureMapper;
import com.investmentresearch.data.persistence.FinIncomeEntity;
import com.investmentresearch.data.persistence.FinIncomeMapper;
import com.investmentresearch.data.persistence.FinMetricsEntity;
import com.investmentresearch.data.persistence.FinMetricsMapper;
import com.investmentresearch.data.persistence.StockMapper;
import com.investmentresearch.data.upstream.UpstreamClient;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 财报快照：新浪三表 → 解析入库 → 派生指标 → 输出。本地缓存超过 7 天、缺表或披露日历
 * 显示有更新的报告期时先抓一次，与 Python {@code finance_service.ensure_financials} 一致。
 */
@Repository
public class FinanceAdapter implements FinancePort {
    // 与 Python 一致。fin_disclosure 暂时没人写，披露日历触发重抓在本阶段不会生效，
    // 新财报最多晚 7 天出现；补上日历同步（Phase 2）后这条豁免自然消失
    static final int DEFAULT_CACHE_DAYS = 7;
    static final int TREND_PERIODS = FinanceSnapshot.DEFAULT_TREND_PERIODS;
    static final String DEFAULT_ENDPOINT =
            "https://quotes.sina.cn/cn/api/openapi.php/CompanyFinanceService.getFinanceReport2022";
    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final TypeReference<LinkedHashMap<String, Object>> ROW =
            new TypeReference<>() {};
    // 实体是驼峰，库里/指标表是下划线；用同一套命名策略双向转换
    private static final ObjectMapper MAPPER =
            new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    private final FinBalanceMapper balanceMapper;
    private final FinIncomeMapper incomeMapper;
    private final FinCashflowMapper cashflowMapper;
    private final FinMetricsMapper metricsMapper;
    private final FinDisclosureMapper disclosureMapper;
    private final StockMapper stockMapper;
    private final UpstreamClient upstreamClient;
    private final String endpoint;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    @Autowired
    public FinanceAdapter(
            FinBalanceMapper balanceMapper,
            FinIncomeMapper incomeMapper,
            FinCashflowMapper cashflowMapper,
            FinMetricsMapper metricsMapper,
            FinDisclosureMapper disclosureMapper,
            StockMapper stockMapper,
            UpstreamClient upstreamClient,
            @Value("${app.finance-url:" + DEFAULT_ENDPOINT + "}") String endpoint,
            PlatformTransactionManager transactionManager) {
        this(
                balanceMapper,
                incomeMapper,
                cashflowMapper,
                metricsMapper,
                disclosureMapper,
                stockMapper,
                upstreamClient,
                endpoint,
                Clock.systemDefaultZone(),
                transactionManager);
    }

    public FinanceAdapter(
            FinBalanceMapper balanceMapper,
            FinIncomeMapper incomeMapper,
            FinCashflowMapper cashflowMapper,
            FinMetricsMapper metricsMapper,
            FinDisclosureMapper disclosureMapper,
            StockMapper stockMapper,
            UpstreamClient upstreamClient,
            String endpoint,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        this.balanceMapper = balanceMapper;
        this.incomeMapper = incomeMapper;
        this.cashflowMapper = cashflowMapper;
        this.metricsMapper = metricsMapper;
        this.disclosureMapper = disclosureMapper;
        this.stockMapper = stockMapper;
        this.upstreamClient = upstreamClient;
        this.endpoint = endpoint;
        this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public ToolResult finance(String rawCode) {
        String code = FinanceSupport.normalize(rawCode);
        if (code.isEmpty()) {
            return ToolResult.failure(rawCode, "请输入股票代码");
        }

        try {
            List<Map<String, Object>> history = freshHistory(code);
            if (history.isEmpty()) {
                return ToolResult.failure(code, code + "：没有可用的财报数据");
            }

            Map<String, Object> snapshot =
                    FinanceSnapshot.build(history, TREND_PERIODS, nameOf(code));
            if (!Boolean.TRUE.equals(snapshot.get("available"))) {
                return ToolResult.failure(code, code + "：没有可用的财报数据");
            }
            return toResult(code, snapshot);
        } catch (RuntimeException error) {
            // 端口不抛异常：上游、读库或组装出的意外错误同样折成失败
            return ToolResult.failure(code, error.getMessage());
        }
    }

    /** 读库，过期或缺表时先抓一次；与 Python {@code ensure_financials} 同一入口。 */
    public List<Map<String, Object>> freshHistory(String code) {
        List<Map<String, Object>> history = history(code);
        if (stale(code, history)) {
            refresh(code);
            history = history(code);
        }
        return history;
    }

    /** 抓三表、算指标、覆盖写入；上游单表失败时保留其余两张表的结果。 */
    void refresh(String code) {
        String fetchedAt = LocalDateTime.now(clock).format(TIMESTAMP);
        String cutoff = FinanceSupport.historyCutoff(LocalDate.now(clock));
        String symbol = sinaSymbol(code);
        Map<String, List<Map<String, Object>>> statements = new LinkedHashMap<>();
        RuntimeException firstError = null;
        for (FinanceStatements.Spec spec : FinanceStatements.STATEMENTS) {
            try {
                List<Map<String, Object>> rows =
                        FinanceStatements.normalize(
                                fetch(code, spec, symbol), spec, code, fetchedAt);
                statements.put(
                        spec.key(),
                        rows.stream()
                                .filter(
                                        row ->
                                                ((String) row.get("report_date"))
                                                                .compareTo(cutoff)
                                                        >= 0)
                                .toList());
            } catch (RuntimeException error) {
                // 缺一张表（例如现金流量表为空）不应该让整次快照失败
                if (firstError == null) {
                    firstError = error;
                }
            }
        }
        if (statements.isEmpty()) {
            throw firstError != null
                    ? firstError
                    : new FinanceException(code + "：没有可用的财报数据");
        }

        List<Map<String, Object>> merged = new ArrayList<>();
        Map<String, Map<String, Object>> byDate = new LinkedHashMap<>();
        for (List<Map<String, Object>> rows : statements.values()) {
            for (Map<String, Object> row : rows) {
                byDate.computeIfAbsent((String) row.get("report_date"), key -> new LinkedHashMap<>())
                        .putAll(row);
            }
        }
        for (String date : byDate.keySet().stream().sorted().toList()) {
            merged.add(byDate.get(date));
        }
        List<Map<String, Object>> metrics = FinanceMetrics.build(merged);

        // 整表覆盖某公司的数据，避免上游删除的报告期残留；删+插必须同事务，
        // 否则中途崩溃会留下新 fetched_at 覆盖残缺历史，7 天内都不会重抓。
        String computedAt = LocalDateTime.now(clock).format(TIMESTAMP);
        transactionTemplate.executeWithoutResult(
                status -> {
                    balanceMapper.delete(Wrappers.<FinBalanceEntity>query().eq("code", code));
                    incomeMapper.delete(Wrappers.<FinIncomeEntity>query().eq("code", code));
                    cashflowMapper.delete(Wrappers.<FinCashflowEntity>query().eq("code", code));
                    metricsMapper.delete(Wrappers.<FinMetricsEntity>query().eq("code", code));
                    for (Map<String, Object> row :
                            statements.getOrDefault("balance", List.of())) {
                        balanceMapper.insert(MAPPER.convertValue(row, FinBalanceEntity.class));
                    }
                    for (Map<String, Object> row :
                            statements.getOrDefault("income", List.of())) {
                        incomeMapper.insert(MAPPER.convertValue(row, FinIncomeEntity.class));
                    }
                    for (Map<String, Object> row :
                            statements.getOrDefault("cashflow", List.of())) {
                        cashflowMapper.insert(MAPPER.convertValue(row, FinCashflowEntity.class));
                    }
                    for (Map<String, Object> row : metrics) {
                        Map<String, Object> withStamp = new LinkedHashMap<>(row);
                        withStamp.put("code", code);
                        withStamp.put("computed_at", computedAt);
                        metricsMapper.insert(MAPPER.convertValue(withStamp, FinMetricsEntity.class));
                    }
                });
    }

    private JsonNode fetch(String code, FinanceStatements.Spec spec, String symbol) {
        String url =
                endpoint
                        + "?paperCode="
                        + symbol
                        + "&source="
                        + sourceOf(spec)
                        + "&type=0&page=1&num=1000";
        try {
            JsonNode root = MAPPER.readTree(upstreamClient.get(url, Map.of()));
            if (root == null) {
                throw new FinanceException(code + " 的" + spec.symbol() + "为空");
            }
            return root.path("result").path("data");
        } catch (JsonProcessingException error) {
            // 200 但返回 HTML/WAF/截断内容：按上游失败处理，不把解析异常抛给调用方
            throw new FinanceException("获取 " + code + " 的" + spec.symbol() + "失败");
        } catch (com.investmentresearch.data.upstream.UpstreamException error) {
            throw new FinanceException(
                    "获取 " + code + " 的" + spec.symbol() + "失败：" + error.getMessage());
        }
    }

    private static String sourceOf(FinanceStatements.Spec spec) {
        return switch (spec.key()) {
            case "balance" -> "fzb";
            case "income" -> "lrb";
            default -> "llb";
        };
    }

    private static String sinaSymbol(String code) {
        String prefix = FinanceSupport.prefix(code);
        if ("UNKNOWN".equals(prefix)) {
            throw new FinanceException("无法识别的股票代码：" + code);
        }
        return prefix.toLowerCase() + code;
    }

    private List<Map<String, Object>> history(String code) {
        Map<String, Map<String, Object>> merged = new LinkedHashMap<>();
        collect(merged, balanceMapper, code);
        collect(merged, incomeMapper, code);
        collect(merged, cashflowMapper, code);
        collect(merged, metricsMapper, code);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String date : merged.keySet().stream().sorted().toList()) {
            rows.add(merged.get(date));
        }
        return rows;
    }

    private static <T> void collect(
            Map<String, Map<String, Object>> merged,
            com.baomidou.mybatisplus.core.mapper.BaseMapper<T> mapper,
            String code) {
        Function<T, Map<String, Object>> toRow = entity -> MAPPER.convertValue(entity, ROW);
        for (T entity : mapper.selectList(Wrappers.<T>query().eq("code", code))) {
            Map<String, Object> row = toRow.apply(entity);
            merged.computeIfAbsent((String) row.get("report_date"), key -> new LinkedHashMap<>())
                    .putAll(row);
        }
    }

    /**
     * 照抄 {@code finance_service._needs_sync}：任一张必填表为空、缓存超过 7 天、或披露日历
     * 已出现比本地更新的报告期时重抓；30 分钟内抓过则先等上游跟上。
     */
    private boolean stale(String code, List<Map<String, Object>> history) {
        if (history.isEmpty()) {
            return true;
        }
        String latest = null;
        for (Map<String, Object> row : history) {
            Object value = row.get("fetched_at");
            if (value instanceof String stamp && (latest == null || stamp.compareTo(latest) > 0)) {
                latest = stamp;
            }
        }
        if (latest == null) {
            return true;
        }
        Duration age;
        try {
            LocalDateTime fetched = LocalDateTime.parse(latest, TIMESTAMP);
            age = Duration.between(fetched, LocalDateTime.now(clock));
        } catch (DateTimeParseException error) {
            return true;
        }
        if (age.compareTo(Duration.ofDays(DEFAULT_CACHE_DAYS)) > 0) {
            return true;
        }
        // 空表也走 30 分钟重试间隔，避免上游真没有该表时每次调用都打三次接口
        if (anyRequiredTableEmpty(code)) {
            return age.compareTo(Duration.ofMinutes(30)) >= 0;
        }
        if (age.compareTo(Duration.ofMinutes(30)) < 0) {
            return false;
        }
        // 表由 V8 建好；真查不出来就是真故障，交给外层折成失败，不再假装新鲜
        String disclosed = disclosureMapper.latestDisclosedPeriod(code);
        String localLatest = (String) history.get(history.size() - 1).get("report_date");
        return disclosed != null
                && localLatest != null
                && disclosed.compareTo(localLatest) > 0;
    }

    private boolean anyRequiredTableEmpty(String code) {
        return count(balanceMapper, code) == 0
                || count(incomeMapper, code) == 0
                || count(cashflowMapper, code) == 0
                || count(metricsMapper, code) == 0;
    }

    private static <T> long count(
            com.baomidou.mybatisplus.core.mapper.BaseMapper<T> mapper, String code) {
        return mapper.selectCount(Wrappers.<T>query().eq("code", code));
    }

    private String nameOf(String code) {
        return stockMapper.selectNameByCode(code);
    }

    private static ToolResult toResult(String code, Map<String, Object> snapshot) {
        Map<String, Object> latest = sub(snapshot, "latest");
        String period = (String) latest.get("report_date");
        Map<String, Object> metrics = sub(snapshot, "metrics");
        Map<String, Object> revenue = sub(metrics, "revenue");
        Map<String, Object> profit = sub(metrics, "net_profit");
        Map<String, Object> cash = sub(metrics, "cash");
        Map<String, Object> safety = sub(metrics, "safety");
        Map<String, Object> detail = sub(metrics, "detail");
        List<Map<String, Object>> trend = trendOf(snapshot);
        Map<String, Object> latestTrend = trend.isEmpty() ? Map.of() : trend.get(trend.size() - 1);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("period", period);
        data.put("revenue_yi", FinanceSupport.yi(num(revenue, "value")));
        data.put("revenue_yoy", FinanceSupport.pct(num(revenue, "yoy")));
        data.put("revenue_sq_yi", FinanceSupport.yi(num(latestTrend, "revenue_sq")));
        data.put("revenue_sq_yoy", FinanceSupport.pct(num(latestTrend, "revenue_sq_yoy")));
        data.put("net_profit_yi", FinanceSupport.yi(num(profit, "value")));
        data.put("net_profit_yoy", FinanceSupport.pct(num(profit, "yoy")));
        data.put("gross_margin", FinanceSupport.pct(metric(metrics, "gross_margin")));
        data.put("net_margin", FinanceSupport.pct(metric(metrics, "net_margin")));
        data.put("roe", FinanceSupport.pct(metric(metrics, "roe")));
        data.put("debt_ratio", FinanceSupport.pct(metric(metrics, "debt_ratio")));
        data.put("ocf_yi", FinanceSupport.yi(num(cash, "operating_cash_flow")));
        data.put("free_cash_flow_yi", FinanceSupport.yi(num(cash, "free_cash_flow")));
        data.put(
                "ocf_to_net_profit",
                FinanceSupport.round(num(cash, "ocf_to_net_profit"), 3));
        data.put("net_debt_yi", FinanceSupport.yi(num(cash, "net_debt")));
        data.put("cash_yi", FinanceSupport.yi(num(cash, "cash_balance")));
        // 财务安全：有息负债、利息保障、商誉与营运资金占用
        data.put("interest_debt_yi", FinanceSupport.yi(num(safety, "interest_debt")));
        data.put("short_term_debt_yi", FinanceSupport.yi(num(safety, "short_term_debt")));
        data.put(
                "interest_coverage",
                FinanceSupport.round(num(safety, "interest_coverage"), 1));
        data.put("interest_coverage_basis", safety.get("interest_coverage_basis"));
        data.put("goodwill_yi", FinanceSupport.yi(num(safety, "goodwill")));
        data.put("goodwill_ratio", FinanceSupport.pct(num(safety, "goodwill_ratio")));
        data.put(
                "accounts_receivable_yi", FinanceSupport.yi(num(safety, "accounts_receivable")));
        data.put(
                "accounts_receivable_yoy",
                FinanceSupport.pct(num(safety, "accounts_receivable_yoy")));
        data.put("inventory_yi", FinanceSupport.yi(num(safety, "inventory")));
        data.put("inventory_yoy", FinanceSupport.pct(num(safety, "inventory_yoy")));
        data.put(
                "cash_to_short_term_debt",
                FinanceSupport.round(num(safety, "cash_to_short_term_debt"), 2));
        // 报表明细：每股收益、权益、产能投入与营运资金科目
        data.put("basic_eps", FinanceSupport.round(num(detail, "basic_eps"), 3));
        data.put("diluted_eps", FinanceSupport.round(num(detail, "diluted_eps"), 3));
        data.put("equity_parent_yi", FinanceSupport.yi(num(detail, "equity_parent")));
        data.put("minority_interest_yi", FinanceSupport.yi(num(detail, "minority_interest")));
        data.put("total_equity_yi", FinanceSupport.yi(num(detail, "total_equity")));
        data.put("total_assets_yi", FinanceSupport.yi(num(detail, "total_assets")));
        data.put("total_liabilities_yi", FinanceSupport.yi(num(detail, "total_liabilities")));
        data.put("fixed_assets_yi", FinanceSupport.yi(num(detail, "fixed_assets")));
        data.put("fixed_assets_yoy", FinanceSupport.pct(num(detail, "fixed_assets_yoy")));
        data.put(
                "construction_in_progress_yi",
                FinanceSupport.yi(num(detail, "construction_in_progress")));
        data.put(
                "construction_in_progress_yoy",
                FinanceSupport.pct(num(detail, "construction_in_progress_yoy")));
        data.put("intangible_assets_yi", FinanceSupport.yi(num(detail, "intangible_assets")));
        data.put(
                "long_term_equity_invest_yi",
                FinanceSupport.yi(num(detail, "long_term_equity_invest")));
        data.put("notes_payable_yi", FinanceSupport.yi(num(detail, "notes_payable")));
        data.put("accounts_payable_yi", FinanceSupport.yi(num(detail, "accounts_payable")));
        data.put(
                "advances_from_customers_yi",
                FinanceSupport.yi(num(detail, "advances_from_customers")));
        data.put("contract_liabilities_yi", FinanceSupport.yi(num(detail, "contract_liabilities")));
        data.put("investment_income_yi", FinanceSupport.yi(num(detail, "investment_income")));
        data.put("fair_value_change_yi", FinanceSupport.yi(num(detail, "fair_value_change")));
        data.put("asset_impairment_yi", FinanceSupport.yi(num(detail, "asset_impairment")));
        data.put("credit_impairment_yi", FinanceSupport.yi(num(detail, "credit_impairment")));
        data.put("treasury_stock_yi", FinanceSupport.yi(num(detail, "treasury_stock")));
        data.put("rd_expense_yi", FinanceSupport.yi(num(detail, "rd_expense")));
        data.put("capex_yi", FinanceSupport.yi(num(detail, "capex")));
        // 营运资金科目同比，和营收增速一起看
        data.put("notes_receivable_yoy", FinanceSupport.pct(num(detail, "notes_receivable_yoy")));
        data.put("prepayments_yoy", FinanceSupport.pct(num(detail, "prepayments_yoy")));
        data.put("other_receivables_yoy", FinanceSupport.pct(num(detail, "other_receivables_yoy")));
        data.put("accounts_payable_yoy", FinanceSupport.pct(num(detail, "accounts_payable_yoy")));
        data.put(
                "contract_liabilities_yoy",
                FinanceSupport.pct(num(detail, "contract_liabilities_yoy")));
        data.put(
                "development_expenditure_yoy",
                FinanceSupport.pct(num(detail, "development_expenditure_yoy")));
        data.put(
                "balance_detail",
                moneyDetail(sub(detail, "balance"), FinanceStatements.BALANCE_DETAIL_FIELDS));
        data.put(
                "income_detail",
                moneyDetail(sub(detail, "income"), FinanceStatements.INCOME_DETAIL_FIELDS));
        data.put(
                "cashflow_detail",
                moneyDetail(sub(detail, "cashflow"), FinanceStatements.CASHFLOW_DETAIL_FIELDS));
        List<Map<String, Object>> trendOut = new ArrayList<>();
        for (Map<String, Object> row : trend) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("period", row.get("report_date"));
            item.put("revenue_sq_yi", FinanceSupport.yi(num(row, "revenue_sq")));
            item.put("revenue_sq_yoy", FinanceSupport.pct(num(row, "revenue_sq_yoy")));
            item.put("gross_margin", FinanceSupport.pct(num(row, "gross_margin")));
            trendOut.add(item);
        }
        data.put("trend", trendOut);

        Object name = snapshot.get("name");
        Source source =
                new Source(
                        "finance:" + code + ":" + period,
                        "财报",
                        (name == null ? "None" : String.valueOf(name))
                                + " "
                                + period
                                + " 财务快照",
                        period,
                        latest.get("fetched_at") instanceof String stamp ? stamp : "");
        return ToolResult.ok(code, period, List.of(source), data);
    }

    private static Map<String, Object> moneyDetail(
            Map<String, Object> values, List<String> fields) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String field : fields) {
            out.put(field + "_yi", FinanceSupport.yi(num(values, field)));
        }
        return out;
    }

    private static Double metric(Map<String, Object> metrics, String key) {
        return num(sub(metrics, key), "value");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> sub(Map<String, Object> map, String key) {
        Object value = map == null ? null : map.get(key);
        return value instanceof Map<?, ?> nested ? (Map<String, Object>) nested : Map.of();
    }

    private static Double num(Map<String, Object> map, String key) {
        Object value = map == null ? null : map.get(key);
        return value instanceof Number number ? number.doubleValue() : null;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> trendOf(Map<String, Object> snapshot) {
        Object value = snapshot.get("trend");
        return value instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }
}
