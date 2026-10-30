package com.investmentresearch.core.tool;

import com.investmentresearch.core.domain.ToolResult;
import com.investmentresearch.core.port.CompanyProfilePort;
import com.investmentresearch.core.service.CompanyResolver;
import java.util.Map;

/** Port of {@code assistant/tools.py:_company_profile}. */
public final class CompanyProfileTool extends CompanyTool {
    private final CompanyProfilePort port;

    public CompanyProfileTool(
            CompanyProfilePort port, CompanyResolver resolver, ToolSchemas schemas) {
        super(resolver, schemas, "get_company_profile");
        this.port = port;
    }

    @Override
    protected ToolResult run(String code, Map<String, Object> arguments) {
        return port.companyProfile(code);
    }
}
