package com.investmentresearch.core.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.investmentresearch.core.domain.StockRef;
import com.investmentresearch.core.domain.ToolResult;
import com.investmentresearch.core.domain.ToolSchema;
import com.investmentresearch.core.port.CompanyDirectory;
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
        ToolSchemas schemas = ToolSchemas.loadDefault();
        CompanyResolver resolver = new CompanyResolver(DIRECTORY);
        List<ResearchTool> tools =
                List.of(
                        new ResolveCompanyTool(DIRECTORY),
                        new CompanyProfileTool(unused(), resolver, schemas),
                        new FinanceTool(unusedFinance(), resolver, schemas),
                        new SegmentsTool(unusedSegment(), resolver, schemas),
                        new PeersTool(unusedPeer(), resolver, schemas),
                        new AnnouncementsTool(unusedAnnouncement(), resolver, schemas),
                        new ValuationTool(unusedValuation(), resolver, schemas));

        ToolRegistry registry = new ToolRegistry(tools);

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
}
