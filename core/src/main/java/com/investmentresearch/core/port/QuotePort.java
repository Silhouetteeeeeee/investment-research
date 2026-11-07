package com.investmentresearch.core.port;

import com.investmentresearch.core.domain.trade.Quote;
import java.util.List;

/** 批量实时报价；停牌/无数据的代码不会出现在结果里。 */
public interface QuotePort {
    List<Quote> quotes(List<String> codes);
}
