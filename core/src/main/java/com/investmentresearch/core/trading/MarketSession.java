package com.investmentresearch.core.trading;

import java.time.LocalTime;
import java.time.ZoneId;

/** A 股交易时段（北京时间）；收盘 15:00 属于非连续交易。 */
public final class MarketSession {
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private static final LocalTime PRE_MARKET_START = LocalTime.of(9, 20);
    private static final LocalTime PRE_MARKET_END = LocalTime.of(9, 45);
    private static final LocalTime AFTERNOON_END = LocalTime.of(15, 0);

    private MarketSession() {}

    public static boolean inContinuousTrading(LocalTime time) {
        return (!time.isBefore(LocalTime.of(9, 30)) && !time.isAfter(LocalTime.of(11, 30)))
                || (!time.isBefore(LocalTime.of(13, 0)) && time.isBefore(AFTERNOON_END));
    }

    /** 09:20 起用报价日期判断今天是否交易日；09:45 仍确认不了就当休市。 */
    public static boolean inPreMarketWindow(LocalTime time) {
        return !time.isBefore(PRE_MARKET_START) && !time.isAfter(PRE_MARKET_END);
    }

    /** 启动补跑窗口：09:25–14:30，错过盘前就尽快补上。 */
    public static boolean inCatchUpWindow(LocalTime time) {
        return !time.isBefore(LocalTime.of(9, 25)) && !time.isAfter(LocalTime.of(14, 30));
    }
}
