package com.investmentresearch.core.port;

import com.investmentresearch.core.domain.ToolResult;

/** Recent announcements. */
public interface AnnouncementPort {
    /** {@code days == null} 表示调用方没给，与 Python {@code arguments.get("days") or 180} 一致。 */
    ToolResult announcements(String code, Integer days);
}
