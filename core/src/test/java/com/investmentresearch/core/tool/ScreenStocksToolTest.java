package com.investmentresearch.core.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.investmentresearch.core.domain.ToolResult;
import com.investmentresearch.core.domain.screen.ScreenFilter;
import com.investmentresearch.core.domain.screen.ScreenSort;
import com.investmentresearch.core.domain.screen.StockScreenQuery;
import com.investmentresearch.core.port.ScreenPort;
import com.investmentresearch.core.port.StockScreenPort;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ScreenStocksToolTest {

    private static ToolSchemas fakeSchemas() {
        return new ToolSchemas(
                Map.of(
                        "screen_stocks",
                        Map.of(
                                "description", "筛选",
                                "parameters", Map.of("type", "object"))));
    }

    /** 记录量化筛选入参；返回值固定成功。 */
    private static final class RecordingQuant implements ScreenPort {
        final List<Integer> limits = new ArrayList<>();
        final List<Double> minAmounts = new ArrayList<>();
        Map<String, Object> data = new LinkedHashMap<>();

        @Override
        public ToolResult screen(int limit, double minAmountYi) {
            limits.add(limit);
            minAmounts.add(minAmountYi);
            return ToolResult.ok("600000", "2026-11-06", List.of(), data);
        }
    }

    /** 记录筛选请求；返回值固定成功。 */
    private static final class RecordingScreen implements StockScreenPort {
        StockScreenQuery query;

        @Override
        public ToolResult screen(StockScreenQuery query) {
            this.query = query;
            return ToolResult.ok(null, "2026-11-06", List.of(), Map.of());
        }
    }

    @Test
    void empty_filters_delegates_to_quant_and_marks_mode() {
        RecordingQuant quant = new RecordingQuant();
        quant.data.put("candidates", List.of("x"));
        RecordingScreen screen = new RecordingScreen();
        ScreenStocksTool tool = new ScreenStocksTool(quant, screen, fakeSchemas());

        ToolResult result = tool.execute(Map.of(), null);

        assertEquals(List.of(20), quant.limits);
        assertEquals(List.of(1.0), quant.minAmounts);
        assertNull(screen.query);
        assertTrue(result.ok());
        assertEquals("quant", result.data().get("mode"));
        // 原结果未被就地修改
        assertFalse(quant.data.containsKey("mode"));
        assertEquals("600000", result.company());
        assertEquals("2026-11-06", result.asOf());
    }

    @Test
    void filters_are_parsed_into_query_and_limit_clamped() {
        RecordingQuant quant = new RecordingQuant();
        RecordingScreen screen = new RecordingScreen();
        ScreenStocksTool tool = new ScreenStocksTool(quant, screen, fakeSchemas());

        ToolResult result =
                tool.execute(
                        Map.of(
                                "filters",
                                List.of(Map.of("field", "pe", "op", "<=", "value", 20)),
                                "sort",
                                List.of(Map.of("field", "roe")),
                                "limit",
                                99),
                        null);

        assertTrue(result.ok());
        assertTrue(quant.limits.isEmpty());
        assertNotNull(screen.query);
        assertEquals(50, screen.query.limit());
        assertEquals(List.of(new ScreenFilter("pe", "<=", 20)), screen.query.filters());
        assertEquals(List.of(new ScreenSort("roe", "desc")), screen.query.sort());
    }

    @Test
    void malformed_filter_payload_fails() {
        RecordingQuant quant = new RecordingQuant();
        RecordingScreen screen = new RecordingScreen();
        ScreenStocksTool tool = new ScreenStocksTool(quant, screen, fakeSchemas());

        ToolResult result =
                tool.execute(Map.of("filters", List.of(Map.of("field", "pe"))), null);

        assertFalse(result.ok());
        assertTrue(result.error().contains("filters"));
        assertTrue(quant.limits.isEmpty());
        assertNull(screen.query);
    }

    @Test
    void schema_is_registered() {
        ToolSchemas schemas = ToolSchemas.loadDefault();
        var schema = schemas.get("screen_stocks");
        assertNotNull(schema.parameters());
        assertTrue(schema.parameters().containsKey("properties"));
    }
}
