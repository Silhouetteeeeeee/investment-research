package com.investmentresearch.core.tool;

import com.investmentresearch.core.domain.ToolResult;
import com.investmentresearch.core.domain.ToolSchema;
import com.investmentresearch.core.domain.screen.ScreenFilter;
import com.investmentresearch.core.domain.screen.ScreenSort;
import com.investmentresearch.core.domain.screen.StockScreenQuery;
import com.investmentresearch.core.port.ScreenPort;
import com.investmentresearch.core.port.StockScreenPort;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 参数化全市场筛选：只做参数解析与钳位，字段/操作符校验由 {@code StockScreen} 负责。
 *
 * <p>不传 filters 时退回量化筛选（{@code screen_candidates}），并在结果里标 {@code mode=quant}。
 */
public final class ScreenStocksTool implements ResearchTool {
    private final ScreenPort quant;
    private final StockScreenPort screen;
    private final ToolSchema schema;

    public ScreenStocksTool(ScreenPort quant, StockScreenPort screen, ToolSchemas schemas) {
        this.quant = quant;
        this.screen = screen;
        this.schema = schemas.get("screen_stocks");
    }

    @Override
    public ToolSchema schema() {
        return schema;
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, String defaultCompany) {
        int limit = Math.max(1, Math.min(50, ToolArguments.intArgument(arguments.get("limit"), 20)));
        Object rawFilters = arguments.get("filters");
        if (rawFilters == null || (rawFilters instanceof List<?> list && list.isEmpty())) {
            ToolResult result = quant.screen(Math.max(3, Math.min(30, limit)), 1.0);
            if (!result.ok()) {
                return result;
            }
            Map<String, Object> data =
                    new LinkedHashMap<>(result.data() == null ? Map.of() : result.data());
            data.put("mode", "quant");
            return new ToolResult(
                    result.ok(),
                    result.company(),
                    result.asOf(),
                    result.sources(),
                    data,
                    result.error(),
                    result.cached());
        }
        if (!(rawFilters instanceof List<?> filters)) {
            return ToolResult.failure(null, "筛选失败：filters 格式错误");
        }
        List<ScreenFilter> parsedFilters = new ArrayList<>();
        for (Object item : filters) {
            if (!(item instanceof Map<?, ?> map) || map.get("field") == null || map.get("op") == null) {
                return ToolResult.failure(null, "筛选失败：filters 格式错误");
            }
            parsedFilters.add(
                    new ScreenFilter(
                            map.get("field").toString(), map.get("op").toString(), map.get("value")));
        }
        List<ScreenSort> parsedSort = new ArrayList<>();
        Object rawSort = arguments.get("sort");
        if (rawSort != null) {
            if (!(rawSort instanceof List<?> sorts)) {
                return ToolResult.failure(null, "筛选失败：sort 格式错误");
            }
            for (Object item : sorts) {
                if (!(item instanceof Map<?, ?> map) || map.get("field") == null) {
                    return ToolResult.failure(null, "筛选失败：sort 格式错误");
                }
                Object order = map.get("order");
                parsedSort.add(
                        new ScreenSort(
                                map.get("field").toString(), order == null ? "desc" : order.toString()));
            }
        }
        return screen.screen(new StockScreenQuery(parsedFilters, parsedSort, limit));
    }
}
