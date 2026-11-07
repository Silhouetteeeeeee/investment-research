package com.investmentresearch.core.trading;

import com.investmentresearch.core.domain.trade.TradingPhase;

/**
 * 当前正在执行的交易阶段。工具只能为这个阶段提交计划草稿；真正的落库由 {@code TradingService}
 * 在 Agent 成功后完成，避免模型中途失败留下半份计划。
 */
public final class TradingContext {
    private volatile String tradeDate;
    private volatile TradingPhase phase;
    private volatile PlanDraft staged;

    public synchronized void begin(String tradeDate, TradingPhase phase) {
        this.tradeDate = tradeDate;
        this.phase = phase;
        this.staged = null;
    }

    public synchronized void clear() {
        this.tradeDate = null;
        this.phase = null;
        this.staged = null;
    }

    /** null 表示不在阶段中。 */
    public String tradeDate() {
        return tradeDate;
    }

    public TradingPhase phase() {
        return phase;
    }

    public synchronized void stage(PlanDraft plan) {
        this.staged = plan;
    }

    /** 未暂存为 null。 */
    public synchronized PlanDraft stagedPlan() {
        return staged;
    }
}
