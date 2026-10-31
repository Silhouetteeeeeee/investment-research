package com.investmentresearch.core.port;

import com.investmentresearch.core.domain.KlineBar;
import java.util.List;

/** 个股日线（前复权），按日期升序。 */
public interface KlinePort {
    /** {@code code} 为 6 位股票代码，{@code days} 已由调用方钳到合理范围。 */
    List<KlineBar> daily(String code, int days);
}
