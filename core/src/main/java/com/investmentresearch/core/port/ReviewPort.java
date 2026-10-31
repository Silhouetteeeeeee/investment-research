package com.investmentresearch.core.port;

import com.investmentresearch.core.domain.ToolResult;
import java.util.Map;

/** 每日判断存档与复盘：对照 {@code research.review.save_daily_judgment / build_review}。 */
public interface ReviewPort {
    /** 生成并保存当天判断；同一天重复保存覆盖旧记录。 */
    ToolResult save(Map<String, Object> strategy);

    /** 读取存档，逐日评估市场、行业与候选股判断。 */
    ToolResult review(int limit, int horizon);
}
