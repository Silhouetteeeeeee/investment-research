package com.investmentresearch.core.tool;

import com.investmentresearch.core.domain.ToolResult;
import com.investmentresearch.core.port.PledgePort;
import com.investmentresearch.core.service.CompanyResolver;
import java.util.Map;

/** Port of {@code assistant/tools.py:_pledges}. */
public final class PledgesTool extends CompanyTool {
    private final PledgePort port;

    public PledgesTool(PledgePort port, CompanyResolver resolver, ToolSchemas schemas) {
        super(resolver, schemas, "get_pledges");
        this.port = port;
    }

    @Override
    protected ToolResult run(String code, Map<String, Object> arguments) {
        int years = clamp(valueOrDefault(arguments, "years", 3), 1, 10);
        return port.pledges(code, years);
    }
}
