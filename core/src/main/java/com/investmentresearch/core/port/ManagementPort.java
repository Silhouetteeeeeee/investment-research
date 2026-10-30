package com.investmentresearch.core.port;

import com.investmentresearch.core.domain.ToolResult;

/** 管理层：{@code stock/management.py:build_management}。 */
public interface ManagementPort {
    ToolResult management(String code, int resumeChars);
}
