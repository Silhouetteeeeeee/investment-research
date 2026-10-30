package com.investmentresearch.core.tool;

import com.investmentresearch.core.domain.ToolResult;
import com.investmentresearch.core.port.AnnouncementPort;
import com.investmentresearch.core.service.CompanyResolver;
import java.util.Map;

/** Port of {@code assistant/tools.py:_announcements}; {@code days} defaults to 180, clamped to 1–365. */
public final class AnnouncementsTool extends CompanyTool {
    private final AnnouncementPort port;

    public AnnouncementsTool(
            AnnouncementPort port, CompanyResolver resolver, ToolSchemas schemas) {
        super(resolver, schemas, "get_announcements");
        this.port = port;
    }

    @Override
    protected ToolResult run(String code, Map<String, Object> arguments) {
        int days = clamp(orDefault(arguments, "days", 180), 1, 365);
        return port.announcements(code, days);
    }
}
