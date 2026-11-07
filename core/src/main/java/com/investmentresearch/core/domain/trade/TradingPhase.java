package com.investmentresearch.core.domain.trade;

/** 交易 Agent 的三个思考阶段；数据库里存小写下划线，与迁移脚本一致。 */
public enum TradingPhase {
    PRE_MARKET("pre_market"),
    MIDDAY("midday"),
    CLOSE("close");

    private final String dbValue;

    TradingPhase(String dbValue) {
        this.dbValue = dbValue;
    }

    public String dbValue() {
        return dbValue;
    }

    public static TradingPhase fromDb(String value) {
        for (TradingPhase phase : values()) {
            if (phase.dbValue.equals(value)) {
                return phase;
            }
        }
        throw new IllegalArgumentException("未知交易阶段：" + value);
    }
}
