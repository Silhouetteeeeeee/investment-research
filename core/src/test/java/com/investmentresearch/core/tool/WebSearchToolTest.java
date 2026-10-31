package com.investmentresearch.core.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.investmentresearch.core.domain.ToolResult;
import com.investmentresearch.core.port.SearchPort;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WebSearchToolTest {
    private static final ToolSchemas SCHEMAS =
            new ToolSchemas(
                    Map.of(
                            "web_search",
                            Map.of(
                                    "description", "search",
                                    "parameters", Map.of("type", "object"))));

    /** 记录每次调用的 query/count；返回值固定成功。 */
    private static final class RecordingPort implements SearchPort {
        final List<String> queries = new ArrayList<>();
        final List<Integer> counts = new ArrayList<>();

        @Override
        public ToolResult search(String query, int count) {
            queries.add(query);
            counts.add(count);
            return ToolResult.ok(null, "2026-10-31", List.of(), Map.of());
        }
    }

    @Test
    void blank_query_fails_without_calling_port() {
        RecordingPort port = new RecordingPort();
        WebSearchTool tool = new WebSearchTool(port, SCHEMAS);

        ToolResult result = tool.execute(Map.of("query", "   "), null);

        assertFalse(result.ok());
        assertTrue(port.queries.isEmpty());
    }

    @Test
    void trims_query_and_defaults_count_to_five() {
        RecordingPort port = new RecordingPort();
        WebSearchTool tool = new WebSearchTool(port, SCHEMAS);

        ToolResult result = tool.execute(Map.of("query", "  贵州茅台 三季报  "), null);

        assertTrue(result.ok());
        assertEquals(List.of("贵州茅台 三季报"), port.queries);
        assertEquals(List.of(5), port.counts);
    }

    @Test
    void clamps_count_to_one_through_ten() {
        RecordingPort port = new RecordingPort();
        WebSearchTool tool = new WebSearchTool(port, SCHEMAS);

        tool.execute(Map.of("query", "a", "count", 99), null);
        tool.execute(Map.of("query", "b", "count", 0), null);
        tool.execute(Map.of("query", "c", "count", "3"), null);

        assertEquals(List.of(10, 1, 3), port.counts);
    }
}
