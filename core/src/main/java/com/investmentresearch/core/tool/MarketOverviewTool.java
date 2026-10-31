package com.investmentresearch.core.tool;

import com.investmentresearch.core.domain.ToolResult;
import com.investmentresearch.core.domain.ToolSchema;
import com.investmentresearch.core.port.MarketPort;
import java.util.Map;

/** Port of {@code assistant/tools.py:get_market_overview}：无公司参数，直接返回整份盘面。 */
public final class MarketOverviewTool implements ResearchTool {
    private final MarketPort market;
    private final ToolSchema schema;

    public MarketOverviewTool(MarketPort market, ToolSchemas schemas) {
        this.market = market;
        this.schema = schemas.get("get_market_overview");
    }

    @Override
    public ToolSchema schema() {
        return schema;
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, String defaultCompany) {
        return market.overview();
    }
}
