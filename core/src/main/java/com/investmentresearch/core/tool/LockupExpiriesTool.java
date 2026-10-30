package com.investmentresearch.core.tool;

import com.investmentresearch.core.domain.ToolResult;
import com.investmentresearch.core.port.LockupPort;
import com.investmentresearch.core.service.CompanyResolver;
import java.util.Map;

/** Port of {@code assistant/tools.py:_lockup_expiries}. */
public final class LockupExpiriesTool extends CompanyTool {
    private final LockupPort port;

    public LockupExpiriesTool(LockupPort port, CompanyResolver resolver, ToolSchemas schemas) {
        super(resolver, schemas, "get_lockup_expiries");
        this.port = port;
    }

    @Override
    protected ToolResult run(String code, Map<String, Object> arguments) {
        int years = clamp(valueOrDefault(arguments, "years", 3), 1, 10);
        int aheadMonths = clamp(valueOrDefault(arguments, "ahead_months", 12), 0, 36);
        return port.lockups(code, years, aheadMonths);
    }
}
