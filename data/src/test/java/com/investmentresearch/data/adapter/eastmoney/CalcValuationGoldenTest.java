package com.investmentresearch.data.adapter.eastmoney;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.investmentresearch.core.domain.StockRef;
import com.investmentresearch.core.domain.ToolResult;
import com.investmentresearch.core.port.CompanyDirectory;
import com.investmentresearch.core.service.CompanyResolver;
import com.investmentresearch.core.tool.CalcValuationTool;
import com.investmentresearch.core.tool.ToolSchemas;
import com.investmentresearch.data.adapter.finance.FinanceAdapter;
import com.investmentresearch.data.persistence.FinBalanceMapper;
import com.investmentresearch.data.persistence.FinCashflowMapper;
import com.investmentresearch.data.persistence.FinDisclosureMapper;
import com.investmentresearch.data.persistence.FinIncomeMapper;
import com.investmentresearch.data.persistence.FinMetricsMapper;
import com.investmentresearch.data.persistence.StockMapper;
import com.investmentresearch.data.persistence.ValHistoryMapper;
import com.investmentresearch.data.persistence.ValPeerMapper;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.PlatformTransactionManager;

/** 三种 mode 的 15 条金标准一起跑；check 需要估值与财报两套数据都对得上。 */
class CalcValuationGoldenTest {
    private static final CompanyDirectory EMPTY_DIRECTORY =
            new CompanyDirectory() {
                @Override
                public Optional<StockRef> byCode(String code) {
                    return Optional.empty();
                }

                @Override
                public Optional<StockRef> byName(String name) {
                    return Optional.empty();
                }

                @Override
                public List<StockRef> all() {
                    return List.of();
                }
            };

    @TempDir Path tempDir;

    private EastmoneyTestSupport.Db db;
    private CalcValuationTool tool;

    @BeforeEach
    void setUp() throws Exception {
        String url = EastmoneyTestSupport.copySnapshot(tempDir);
        db = EastmoneyTestSupport.openDb(url);
        for (String code : List.of("000858", "600600", "601318", "601606", "605499")) {
            EastmoneyTestSupport.makeCachesFresh(db.session(), code);
            EastmoneyTestSupport.exec(
                    db.session(),
                    "update fin_balance set fetched_at = ? where code = ?",
                    "2026-10-31 09:00:00",
                    code);
            EastmoneyTestSupport.exec(
                    db.session(),
                    "update fin_income set fetched_at = ? where code = ?",
                    "2026-10-31 09:00:00",
                    code);
            EastmoneyTestSupport.exec(
                    db.session(),
                    "update fin_cashflow set fetched_at = ? where code = ?",
                    "2026-10-31 09:00:00",
                    code);
        }
        EastmoneyTestSupport.exec(db.session(), "delete from fin_disclosure");

        PlatformTransactionManager transactionManager = db.transactionManager();
        String dataPath = EastmoneyTestSupport.DATA_PATH;
        ValuationSupport support =
                new ValuationSupport(
                        db.session().getMapper(ValHistoryMapper.class),
                        db.session().getMapper(ValPeerMapper.class),
                        EastmoneyTestSupport.offline(),
                        "http://unused" + dataPath,
                        EastmoneyTestSupport.CLOCK_END,
                        transactionManager);
        StockMapper stockMapper = db.session().getMapper(StockMapper.class);
        ValuationAdapter valuation = new ValuationAdapter(support, stockMapper);
        FinanceAdapter finance =
                new FinanceAdapter(
                        db.session().getMapper(FinBalanceMapper.class),
                        db.session().getMapper(FinIncomeMapper.class),
                        db.session().getMapper(FinCashflowMapper.class),
                        db.session().getMapper(FinMetricsMapper.class),
                        db.session().getMapper(FinDisclosureMapper.class),
                        stockMapper,
                        EastmoneyTestSupport.offline(),
                        "http://unused",
                        EastmoneyTestSupport.CLOCK_END,
                        transactionManager);
        tool =
                new CalcValuationTool(
                        valuation,
                        new CalcValuationAdapter(support, finance, stockMapper),
                        new CompanyResolver(EMPTY_DIRECTORY),
                        ToolSchemas.loadDefault());
    }

    @Test
    void every_mode_matches_golden() throws Exception {
        List<Path> goldens = EastmoneyTestSupport.goldens("calc_valuation");
        assertThat(goldens).hasSize(15);
        for (Path file : goldens) {
            JsonNode golden = EastmoneyTestSupport.golden(file);
            String code = golden.path("default_company").asText();
            @SuppressWarnings("unchecked")
            Map<String, Object> arguments = EastmoneyTestSupport.MAPPER.convertValue(
                    golden.path("arguments"), Map.class);
            ToolResult actual = tool.execute(arguments, code);
            EastmoneyTestSupport.assertMatchesGolden(file, actual);
        }
    }
}
