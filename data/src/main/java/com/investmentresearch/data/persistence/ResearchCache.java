package com.investmentresearch.data.persistence;

/** 阶段 2 的缓存表；表名与键列只在这里定义，SQL 不接受外部传入的表名。 */
public enum ResearchCache {
    MANAGEMENT("company_management", null),
    SHAREHOLDERS("company_shareholders", null),
    DIVIDENDS("company_dividends", null),
    INSIDER_TRADES("company_insider_trades", null),
    BUYBACKS("company_buybacks", null),
    LOCKUPS("company_lockups", null),
    PLEDGES("company_pledges", null),
    FORECASTS("company_forecasts", null),
    /** 机构调研按窗口年数缓存，Python 的 `company_research(code, years)`。 */
    RESEARCH("company_research", "years"),
    /** 研报按天数窗口缓存。 */
    REPORTS("company_reports", "days");

    private final String table;
    private final String keyColumn;

    ResearchCache(String table, String keyColumn) {
        this.table = table;
        this.keyColumn = keyColumn;
    }

    public String table() {
        return table;
    }

    /** {@code null} 表示这张表只以 code 为键。 */
    public String keyColumn() {
        return keyColumn;
    }
}
