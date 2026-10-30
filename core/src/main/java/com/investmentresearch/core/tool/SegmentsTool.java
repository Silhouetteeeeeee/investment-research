package com.investmentresearch.core.tool;

import com.investmentresearch.core.domain.ToolResult;
import com.investmentresearch.core.port.SegmentPort;
import com.investmentresearch.core.service.CompanyResolver;
import java.util.Map;

/** Port of {@code assistant/tools.py:_segments}; {@code years} defaults to 5, clamped to 1–10. */
public final class SegmentsTool extends CompanyTool {
    private final SegmentPort port;

    public SegmentsTool(SegmentPort port, CompanyResolver resolver, ToolSchemas schemas) {
        super(resolver, schemas, "get_segments");
        this.port = port;
    }

    @Override
    protected ToolResult run(String code, Map<String, Object> arguments) {
        int years = clamp(orDefault(arguments, "years", 5), 1, 10);
        return port.segments(code, years);
    }
}
