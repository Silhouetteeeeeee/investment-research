package com.investmentresearch.core.port;

import com.investmentresearch.core.domain.StockRef;
import java.util.List;
import java.util.Optional;

/** Local stock master-table lookups. */
public interface CompanyDirectory {
    Optional<StockRef> byCode(String code);

    Optional<StockRef> byName(String name);

    List<StockRef> all();
}
