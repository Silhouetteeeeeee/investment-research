package com.investmentresearch.core.tool;

import com.investmentresearch.core.domain.ToolResult;
import com.investmentresearch.core.domain.ToolSchema;
import com.investmentresearch.core.port.SectorPort;
import java.util.Map;

/** Port of {@code assistant/tools.py:get_sector_rotation}：无公司参数，limit 默认 8、钳在 3..20。 */
public final class SectorRotationTool implements ResearchTool {
    private final SectorPort sectors;
    private final ToolSchema schema;

    public SectorRotationTool(SectorPort sectors, ToolSchemas schemas) {
        this.sectors = sectors;
        this.schema = schemas.get("get_sector_rotation");
    }

    @Override
    public ToolSchema schema() {
        return schema;
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, String defaultCompany) {
        Object raw = arguments.get("limit");
        int limit = raw == null ? 8 : limitOf(raw);
        return sectors.sectors(Math.max(3, Math.min(20, limit)));
    }

    private static int limitOf(Object raw) {
        if (raw instanceof Number number) {
            return (int) number.doubleValue();
        }
        try {
            return Integer.parseInt(raw.toString().trim());
        } catch (NumberFormatException error) {
            return 8;
        }
    }
}
