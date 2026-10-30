package com.investmentresearch.core.tool;

import com.investmentresearch.core.domain.ToolResult;
import com.investmentresearch.core.port.ManagementPort;
import com.investmentresearch.core.service.CompanyResolver;
import java.util.Map;

/** Port of {@code assistant/tools.py:_management}. */
public final class ManagementTool extends CompanyTool {
    private final ManagementPort port;

    public ManagementTool(ManagementPort port, CompanyResolver resolver, ToolSchemas schemas) {
        super(resolver, schemas, "get_management");
        this.port = port;
    }

    @Override
    protected ToolResult run(String code, Map<String, Object> arguments) {
        int resumeChars = clamp(valueOrDefault(arguments, "resume_chars", 300), 0, 2000);
        return port.management(code, resumeChars);
    }
}
