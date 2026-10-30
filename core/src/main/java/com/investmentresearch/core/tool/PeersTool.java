package com.investmentresearch.core.tool;

import com.investmentresearch.core.domain.ToolResult;
import com.investmentresearch.core.port.PeerPort;
import com.investmentresearch.core.service.CompanyResolver;
import java.util.Map;

/** Port of {@code assistant/tools.py:_peers}; {@code limit} defaults to 8, clamped to 3–20. */
public final class PeersTool extends CompanyTool {
    private final PeerPort port;

    public PeersTool(PeerPort port, CompanyResolver resolver, ToolSchemas schemas) {
        super(resolver, schemas, "get_peers");
        this.port = port;
    }

    @Override
    protected ToolResult run(String code, Map<String, Object> arguments) {
        int limit = clamp(valueOrDefault(arguments, "limit", 8), 3, 20);
        return port.peers(code, limit);
    }
}
