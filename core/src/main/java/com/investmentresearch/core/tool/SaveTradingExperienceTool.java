package com.investmentresearch.core.tool;

import com.investmentresearch.core.domain.ToolResult;
import com.investmentresearch.core.domain.ToolSchema;
import com.investmentresearch.core.domain.trade.TradingPhase;
import com.investmentresearch.core.trading.TradingContext;
import com.investmentresearch.core.trading.TradingExperience;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** 收盘阶段把当天教训合并进长期经验文档；整篇覆盖写，盘前/午盘只读不写。 */
public final class SaveTradingExperienceTool implements ResearchTool {
    private final TradingExperience experience;
    private final TradingContext context;
    private final ToolSchemas schemas;

    public SaveTradingExperienceTool(
            TradingExperience experience, TradingContext context, ToolSchemas schemas) {
        this.experience = experience;
        this.context = context;
        this.schemas = schemas;
    }

    @Override
    public ToolSchema schema() {
        return schemas.get("save_trading_experience");
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, String defaultCompany) {
        if (context.phase() != TradingPhase.CLOSE) {
            return ToolResult.failure(null, "经验只在收盘阶段维护，当前阶段不写");
        }
        String content = text(arguments.get("content"));
        if (content == null) {
            return ToolResult.failure(null, "经验正文不能为空");
        }
        try {
            String updatedAt = experience.save(content);
            return ToolResult.ok(
                    null,
                    context.tradeDate(),
                    List.of(),
                    Map.of(
                            "saved", true,
                            "date", context.tradeDate(),
                            "bytes", content.strip().getBytes(StandardCharsets.UTF_8).length,
                            "updated_at", updatedAt));
        } catch (IllegalArgumentException | UncheckedIOException error) {
            // 经验是尽力而为：写失败只告诉模型原因，不让整个收盘阶段失败
            return ToolResult.failure(null, error.getMessage());
        }
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString().strip();
        return text.isEmpty() ? null : text;
    }
}
