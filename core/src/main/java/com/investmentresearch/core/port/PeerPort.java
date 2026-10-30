package com.investmentresearch.core.port;

import com.investmentresearch.core.domain.ToolResult;

/** Peer cohort comparison. */
public interface PeerPort {
    ToolResult peers(String code, int limit);
}
