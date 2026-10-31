package com.investmentresearch.core.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.investmentresearch.core.domain.StockRef;
import com.investmentresearch.core.domain.ToolResult;
import com.investmentresearch.core.domain.ToolSchema;
import com.investmentresearch.core.port.AnalystViewPort;
import com.investmentresearch.core.port.BuybackPort;
import com.investmentresearch.core.port.CompanyDirectory;
import com.investmentresearch.core.port.DividendPort;
import com.investmentresearch.core.port.FinancialHistoryPort;
import com.investmentresearch.core.port.ForecastPort;
import com.investmentresearch.core.port.InsiderTradePort;
import com.investmentresearch.core.port.LockupPort;
import com.investmentresearch.core.port.ManagementPort;
import com.investmentresearch.core.port.MarketPort;
import com.investmentresearch.core.port.NewsPort;
import com.investmentresearch.core.port.PledgePort;
import com.investmentresearch.core.port.ResearchMemoryPort;
import com.investmentresearch.core.port.ResearchNotesPort;
import com.investmentresearch.core.port.ResearchPort;
import com.investmentresearch.core.port.ReviewPort;
import com.investmentresearch.core.port.ScreenPort;
import com.investmentresearch.core.port.SectorPort;
import com.investmentresearch.core.port.ShareholderPort;
import com.investmentresearch.core.port.StrategyPort;
import com.investmentresearch.core.port.ValuationCheckPort;
import com.investmentresearch.core.port.ValuationPort;
import com.investmentresearch.core.service.CompanyResolver;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The registered schemas must be byte-for-byte the Python export, key order included. */
class ToolSchemaParityTest {

    private static final CompanyDirectory DIRECTORY =
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

    @Test
    void every_registered_tool_schema_equals_python_json_exactly() throws IOException {
        Map<String, Map<String, Object>> exported = readExport();
        ToolRegistry registry = registry();

        // 注册表为空时下面的循环一次都不跑，测试会假通过
        assertFalse(registry.schemas().isEmpty());
        for (ToolSchema schema : registry.schemas()) {
            Map<String, Object> expected = exported.get(schema.name());
            assertNotNull(expected, schema.name());
            Map<String, Object> actual = new LinkedHashMap<>();
            actual.put("name", schema.name());
            actual.put("description", schema.description());
            actual.put("parameters", schema.parameters());
            assertSameJson(expected, actual, schema.name());
        }
    }

    /** 顺序等于 Python {@code schemas()}（{@code load_skill} 由 harness 提供，不在注册表里）。 */
    @Test
    void registry_matches_the_python_tool_order() {
        assertEquals(
                List.of(
                        "resolve_company",
                        "get_company_profile",
                        "get_finance",
                        "get_financial_history",
                        "get_segments",
                        "get_management",
                        "get_shareholders",
                        "get_peers",
                        "get_dividends",
                        "get_insider_trades",
                        "get_buybacks",
                        "get_lockup_expiries",
                        "get_pledges",
                        "get_earnings_forecasts",
                        "get_institutional_research",
                        "get_research_notes",
                        "get_analyst_views",
                        "get_announcements",
                        "get_valuation",
                        "calc_valuation",
                        "get_market_overview",
                        "get_news_events",
                        "get_sector_rotation",
                        "screen_candidates",
                        "build_strategy_plan",
                        "save_daily_judgment",
                        "review_research_reports",
                        "list_research_memory",
                        "read_research_memory",
                        "append_research_record",
                        "update_research_record",
                        "record_conclusion_change"),
                registry().schemas().stream().map(ToolSchema::name).toList());
    }

    private static ToolRegistry registry() {
        ToolSchemas schemas = ToolSchemas.loadDefault();
        CompanyResolver resolver = new CompanyResolver(DIRECTORY);
        ValuationPort valuation = unusedValuation();
        ResearchMemoryPort memory = org.mockito.Mockito.mock(ResearchMemoryPort.class);
        return new ToolRegistry(
                List.of(
                        new ResolveCompanyTool(DIRECTORY, resolver, schemas),
                        new CompanyProfileTool(unused(), resolver, schemas),
                        new FinanceTool(unusedFinance(), resolver, schemas),
                        new FinancialHistoryTool(unusedHistory(), resolver, schemas),
                        new SegmentsTool(unusedSegment(), resolver, schemas),
                        new ManagementTool(unusedManagement(), resolver, schemas),
                        new ShareholdersTool(unusedShareholder(), resolver, schemas),
                        new PeersTool(unusedPeer(), resolver, schemas),
                        new DividendsTool(unusedDividend(), resolver, schemas),
                        new InsiderTradesTool(unusedInsider(), resolver, schemas),
                        new BuybacksTool(unusedBuyback(), resolver, schemas),
                        new LockupExpiriesTool(unusedLockup(), resolver, schemas),
                        new PledgesTool(unusedPledge(), resolver, schemas),
                        new EarningsForecastsTool(unusedForecast(), resolver, schemas),
                        new InstitutionalResearchTool(unusedResearch(), resolver, schemas),
                        new ResearchNotesTool(unusedNotes(), resolver, schemas),
                        new AnalystViewsTool(unusedAnalyst(), resolver, schemas),
                        new AnnouncementsTool(unusedAnnouncement(), resolver, schemas),
                        new ValuationTool(valuation, resolver, schemas),
                        new CalcValuationTool(valuation, unusedCheck(), resolver, schemas),
                        new MarketOverviewTool(unusedMarket(), schemas),
                        new NewsEventsTool(unusedNews(), schemas),
                        new SectorRotationTool(unusedSector(), schemas),
                        new ScreenCandidatesTool(unusedScreen(), schemas),
                        new StrategyPlanTool(unusedStrategy(), schemas),
                        new SaveJudgmentTool(unusedReview(), schemas),
                        new ReviewReportsTool(unusedReview(), schemas),
                        new ListResearchMemoryTool(memory, resolver, schemas),
                        new ReadResearchMemoryTool(memory, resolver, schemas),
                        new AppendResearchRecordTool(memory, DIRECTORY, resolver, schemas),
                        new UpdateResearchRecordTool(memory, resolver, schemas),
                        new RecordConclusionChangeTool(memory, resolver, schemas)));
    }

    private static Map<String, Map<String, Object>> readExport() throws IOException {
        try (InputStream in = ToolSchemas.class.getResourceAsStream("/tool-schemas.json")) {
            assertNotNull(in);
            return new ObjectMapper()
                    .readValue(in, new TypeReference<Map<String, Map<String, Object>>>() {});
        }
    }

    /**
     * Recursive deep compare: maps must carry the same keys in the same order, lists the same
     * elements in the same order; any extra, missing or reordered entry fails.
     */
    private static void assertSameJson(Object expected, Object actual, String path) {
        if (expected instanceof Map<?, ?> expectedMap && actual instanceof Map<?, ?> actualMap) {
            assertEquals(
                    new ArrayList<>(expectedMap.keySet()),
                    new ArrayList<>(actualMap.keySet()),
                    path + " key order");
            for (Object key : expectedMap.keySet()) {
                assertSameJson(expectedMap.get(key), actualMap.get(key), path + "." + key);
            }
        } else if (expected instanceof List<?> expectedList && actual instanceof List<?> actualList) {
            assertEquals(expectedList.size(), actualList.size(), path + " size");
            for (int i = 0; i < expectedList.size(); i++) {
                assertSameJson(expectedList.get(i), actualList.get(i), path + "[" + i + "]");
            }
        } else {
            assertEquals(expected, actual, path);
        }
    }

    private static com.investmentresearch.core.port.CompanyProfilePort unused() {
        return code -> ToolResult.failure(code, "unused");
    }

    private static com.investmentresearch.core.port.FinancePort unusedFinance() {
        return code -> ToolResult.failure(code, "unused");
    }

    private static com.investmentresearch.core.port.ValuationPort unusedValuation() {
        return code -> ToolResult.failure(code, "unused");
    }

    private static com.investmentresearch.core.port.SegmentPort unusedSegment() {
        return (code, years) -> ToolResult.failure(code, "unused");
    }

    private static com.investmentresearch.core.port.PeerPort unusedPeer() {
        return (code, limit) -> ToolResult.failure(code, "unused");
    }

    private static com.investmentresearch.core.port.AnnouncementPort unusedAnnouncement() {
        return (code, days) -> ToolResult.failure(code, "unused");
    }

    private static FinancialHistoryPort unusedHistory() {
        return (code, years) -> ToolResult.failure(code, "unused");
    }

    private static ManagementPort unusedManagement() {
        return (code, resumeChars) -> ToolResult.failure(code, "unused");
    }

    private static ShareholderPort unusedShareholder() {
        return (code, periods) -> ToolResult.failure(code, "unused");
    }

    private static DividendPort unusedDividend() {
        return (code, years) -> ToolResult.failure(code, "unused");
    }

    private static InsiderTradePort unusedInsider() {
        return (code, years) -> ToolResult.failure(code, "unused");
    }

    private static BuybackPort unusedBuyback() {
        return (code, years) -> ToolResult.failure(code, "unused");
    }

    private static LockupPort unusedLockup() {
        return (code, years, aheadMonths) -> ToolResult.failure(code, "unused");
    }

    private static PledgePort unusedPledge() {
        return (code, years) -> ToolResult.failure(code, "unused");
    }

    private static ForecastPort unusedForecast() {
        return (code, years) -> ToolResult.failure(code, "unused");
    }

    private static ResearchPort unusedResearch() {
        return (code, years) -> ToolResult.failure(code, "unused");
    }

    private static ResearchNotesPort unusedNotes() {
        return (code, years, events, maxChars) -> ToolResult.failure(code, "unused");
    }

    private static AnalystViewPort unusedAnalyst() {
        return (code, days) -> ToolResult.failure(code, "unused");
    }

    private static ValuationCheckPort unusedCheck() {
        return code -> ToolResult.failure(code, "unused");
    }

    private static MarketPort unusedMarket() {
        return () -> ToolResult.failure(null, "unused");
    }

    private static NewsPort unusedNews() {
        return limit -> ToolResult.failure(null, "unused");
    }

    private static SectorPort unusedSector() {
        return limit -> ToolResult.failure(null, "unused");
    }

    private static ScreenPort unusedScreen() {
        return (limit, minAmountYi) -> ToolResult.failure(null, "unused");
    }

    private static StrategyPort unusedStrategy() {
        return (profile, marketState, riskLevel, holdings) -> ToolResult.failure(null, "unused");
    }

    private static ReviewPort unusedReview() {
        return new ReviewPort() {
            @Override
            public ToolResult save(java.util.Map<String, Object> strategy) {
                return ToolResult.failure(null, "unused");
            }

            @Override
            public ToolResult review(int limit, int horizon) {
                return ToolResult.failure(null, "unused");
            }
        };
    }
}
