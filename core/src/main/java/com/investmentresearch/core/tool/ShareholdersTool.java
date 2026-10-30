package com.investmentresearch.core.tool;

import com.investmentresearch.core.domain.ToolResult;
import com.investmentresearch.core.port.ShareholderPort;
import com.investmentresearch.core.service.CompanyResolver;
import java.util.Map;

/** Port of {@code assistant/tools.py:_shareholders}. */
public final class ShareholdersTool extends CompanyTool {
    private final ShareholderPort port;

    public ShareholdersTool(ShareholderPort port, CompanyResolver resolver, ToolSchemas schemas) {
        super(resolver, schemas, "get_shareholders");
        this.port = port;
    }

    @Override
    protected ToolResult run(String code, Map<String, Object> arguments) {
        int periods = clamp(valueOrDefault(arguments, "periods", 8), 1, 10);
        return port.shareholders(code, periods);
    }
}
