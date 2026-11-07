package com.investmentresearch.core.trading;

import com.investmentresearch.core.domain.trade.Side;
import java.util.List;

/** 模型通过 {@code save_trade_plan} 提交、尚未落库的计划草稿。 */
public record PlanDraft(String notes, List<Item> items) {

    public PlanDraft {
        notes = notes == null ? "" : notes;
        items = items == null ? List.of() : List.copyOf(items);
    }

    public record Item(
            String code,
            String name,
            Side side,
            Long triggerPriceCents,
            int shares,
            Long stopLossCents,
            Long takeProfitCents,
            String reason) {}
}
