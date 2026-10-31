package com.investmentresearch.app.api;

import com.investmentresearch.core.tool.ToolRegistry;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** System and per-tool metrics for the in-page monitor. Read-only and authenticated like /api/**. */
@RestController
public class MetricsController {
    private final ToolRegistry tools;

    public MetricsController(ToolRegistry tools) {
        this.tools = tools;
    }

    @GetMapping("/api/metrics")
    public Map<String, Object> metrics() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("system", system());
        body.put("tools", tools.metrics().snapshot());
        return body;
    }

    private static Map<String, Object> system() {
        com.sun.management.OperatingSystemMXBean os =
                (com.sun.management.OperatingSystemMXBean)
                        ManagementFactory.getOperatingSystemMXBean();
        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        MemoryUsage nonHeap = ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage();
        long memoryTotal = os.getTotalMemorySize();
        long memoryFree = os.getFreeMemorySize();

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("cpu", os.getCpuLoad());
        metrics.put("processCpu", os.getProcessCpuLoad());
        metrics.put("cores", os.getAvailableProcessors());
        metrics.put("heapUsed", heap.getUsed());
        metrics.put("heapMax", heap.getMax());
        metrics.put("nonHeapUsed", nonHeap.getUsed());
        metrics.put("memoryUsed", Math.max(0, memoryTotal - memoryFree));
        metrics.put("memoryTotal", memoryTotal);
        metrics.put("uptimeMs", ManagementFactory.getRuntimeMXBean().getUptime());
        metrics.put("threads", ManagementFactory.getThreadMXBean().getThreadCount());
        return metrics;
    }
}
