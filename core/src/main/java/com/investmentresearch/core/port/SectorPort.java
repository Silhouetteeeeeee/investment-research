package com.investmentresearch.core.port;

import com.investmentresearch.core.domain.ToolResult;

/** 行业轮动取数端口（{@code research.sector_rotation.build_sector_rotation}）。 */
public interface SectorPort {
    /** {@code limit} 已由工具层钳到 3..20。 */
    ToolResult sectors(int limit);
}
