package com.investmentresearch.core.port;

import com.investmentresearch.core.domain.ToolResult;

/** Recent announcements. */
public interface AnnouncementPort {
    ToolResult announcements(String code, int days);
}
