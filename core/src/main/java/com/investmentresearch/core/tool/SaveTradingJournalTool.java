package com.investmentresearch.core.tool;

import com.investmentresearch.core.domain.ToolResult;
import com.investmentresearch.core.domain.ToolSchema;
import com.investmentresearch.core.domain.trade.JournalEntry;
import com.investmentresearch.core.domain.trade.TradingPhase;
import com.investmentresearch.core.port.TradingRepository;
import com.investmentresearch.core.trading.TradingContext;
import java.util.List;
import java.util.Map;

/** 午盘/收盘复盘的落库入口；盘前只做计划，不接受复盘。 */
public final class SaveTradingJournalTool implements ResearchTool {
    private final TradingRepository repo;
    private final TradingContext context;
    private final ToolSchemas schemas;

    public SaveTradingJournalTool(
            TradingRepository repo, TradingContext context, ToolSchemas schemas) {
        this.repo = repo;
        this.context = context;
        this.schemas = schemas;
    }

    @Override
    public ToolSchema schema() {
        return schemas.get("save_trading_journal");
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, String defaultCompany) {
        TradingPhase phase = context.phase();
        if (phase == null || context.tradeDate() == null) {
            return ToolResult.failure(null, "当前不在交易阶段，不能写复盘");
        }
        if (phase == TradingPhase.PRE_MARKET) {
            return ToolResult.failure(null, "盘前不能写复盘：盘前只做交易计划");
        }
        String text = text(arguments.get("text"));
        if (text == null) {
            return ToolResult.failure(null, "复盘正文不能为空");
        }
        repo.saveJournal(new JournalEntry(0, context.tradeDate(), phase, text, null));
        return ToolResult.ok(
                null,
                context.tradeDate(),
                List.of(),
                Map.of("saved", true, "phase", phase.dbValue(), "date", context.tradeDate()));
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }
}
