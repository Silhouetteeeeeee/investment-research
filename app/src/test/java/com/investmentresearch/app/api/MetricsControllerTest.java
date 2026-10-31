package com.investmentresearch.app.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.investmentresearch.core.tool.ToolMetrics;
import com.investmentresearch.core.tool.ToolRegistry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MetricsControllerTest {

    @Test
    @SuppressWarnings("unchecked")
    void metrics_reports_system_usage_and_tool_stats() {
        ToolMetrics toolMetrics = new ToolMetrics();
        toolMetrics.record("get_finance", 12, true);
        MetricsController controller = new MetricsController(new ToolRegistry(List.of(), toolMetrics));

        Map<String, Object> body = controller.metrics();

        Map<String, Object> system = (Map<String, Object>) body.get("system");
        assertThat(system)
                .containsKeys(
                        "cpu",
                        "processCpu",
                        "cores",
                        "heapUsed",
                        "heapMax",
                        "nonHeapUsed",
                        "memoryUsed",
                        "memoryTotal",
                        "uptimeMs",
                        "threads");
        assertThat((List<?>) body.get("tools")).hasSize(1);
    }
}
