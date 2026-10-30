package com.investmentresearch.core.port;

import com.investmentresearch.core.domain.ToolResult;

/** Recent announcements. */
public interface AnnouncementPort {
    /** {@code Integer} 只给直接调用方留 null 兜底；生产路径 AnnouncementsTool 已给 180 并钳成 int。 */
    ToolResult announcements(String code, Integer days);
}
