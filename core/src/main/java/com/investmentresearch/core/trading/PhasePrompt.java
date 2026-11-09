package com.investmentresearch.core.trading;

import com.investmentresearch.core.domain.trade.JournalEntry;
import com.investmentresearch.core.domain.trade.PlanItem;
import com.investmentresearch.core.domain.trade.Position;
import com.investmentresearch.core.domain.trade.TradeAccount;
import com.investmentresearch.core.domain.trade.TradePlan;
import com.investmentresearch.core.domain.trade.TradingPhase;
import com.investmentresearch.core.port.TradingRepository;
import java.util.List;
import java.util.Locale;

/** 阶段用户提示词：状态快照 + 当日约束 + 本阶段要做的事。 */
public final class PhasePrompt {
    private PhasePrompt() {}

    public static String userMessage(
            TradingRepository repo, TradingPhase phase, String tradeDate, String experience) {
        StringBuilder out = new StringBuilder();
        out.append("交易日：").append(tradeDate).append('\n');
        out.append("当前阶段：").append(label(phase)).append("\n\n");

        TradeAccount account = repo.account();
        out.append("账户：现金 ").append(yuan(account.cashCents()))
                .append(" 元，初始 ").append(yuan(account.initialCents())).append(" 元\n");
        List<Position> positions = repo.positions();
        if (positions.isEmpty()) {
            out.append("持仓：空仓\n");
        } else {
            out.append("持仓：\n");
            for (Position position : positions) {
                out.append("- ").append(position.code()).append(' ')
                        .append(position.name()).append(' ').append(position.shares())
                        .append(" 股，成本 ").append(yuan(position.avgCostCents())).append(" 元\n");
            }
        }

        out.append("当前计划：");
        TradePlan plan = repo.activePlan(tradeDate);
        if (plan == null) {
            out.append("无\n");
        } else {
            out.append("v").append(plan.version()).append(' ').append(plan.status());
            if (!plan.notes().isEmpty()) {
                out.append("（").append(plan.notes()).append("）");
            }
            out.append('\n');
            for (PlanItem item : plan.items()) {
                out.append("- ").append(item.side()).append(' ').append(item.code()).append(' ')
                        .append(item.name()).append(' ').append(item.shares()).append(" 股");
                if (item.triggerPriceCents() != null) {
                    out.append("，限价 ").append(yuan(item.triggerPriceCents()));
                }
                if (item.stopLossCents() != null) {
                    out.append("，止损 ").append(yuan(item.stopLossCents()));
                }
                if (item.takeProfitCents() != null) {
                    out.append("，止盈 ").append(yuan(item.takeProfitCents()));
                }
                out.append("，").append(item.status()).append("（").append(item.source())
                        .append("）\n");
            }
        }

        out.append("\n交易经验（你自己维护；收盘阶段用 save_trading_experience 整篇更新）：\n");
        if (experience == null || experience.isBlank()) {
            out.append("- 暂无经验\n");
        } else {
            out.append(experience.strip()).append('\n');
        }

        out.append("\n最近复盘：\n");
        List<JournalEntry> journals = repo.recentJournals(5);
        if (journals.isEmpty()) {
            out.append("- 无\n");
        } else {
            for (JournalEntry journal : journals) {
                out.append("- ").append(journal.tradeDate()).append(' ')
                        .append(journal.phase() == null ? "" : journal.phase().dbValue())
                        .append("：").append(journal.text()).append('\n');
            }
        }

        out.append("\n今日约束：单票不超过权益 20%，最多 ").append(TradingRules.MAX_POSITIONS)
                .append(" 只持仓；买入股数必须是 ").append(TradingRules.LOT)
                .append(" 的整数倍；T+1，今天买的今天不能卖；佣金万 2.5（最低 5 元），")
                .append("卖出印花税千 1。\n");
        out.append('\n').append(instructions(phase));
        return out.toString();
    }

    static String label(TradingPhase phase) {
        return switch (phase) {
            case PRE_MARKET -> "盘前";
            case MIDDAY -> "午盘";
            case CLOSE -> "收盘";
        };
    }

    private static String instructions(TradingPhase phase) {
        return switch (phase) {
            case PRE_MARKET -> """
                    盘前任务：
                    1. 先调用 get_trading_state，确认现金、持仓和可用股份。
                    2. 看 get_market_overview 判断今天适不适合新开仓。
                    3. 用 screen_candidates 和 list_research_memory 找「好公司 + 好价格」，
                       候选必须再读档案/估值/财务，不允许只看量价分。
                    4. 最多选 5 只，每只给出限价、股数、reason、止损价、止盈价。
                    5. 每个现有持仓都要有 SELL 项（可以是「继续持有、不设止损」的显式项）。
                    6. 恰好调用一次 save_trade_plan，失败就按错误提示改正后重试一次。
                    7. 最后写一段带 [[source_id]] 引用的计划摘要。
                    """;
            case MIDDAY -> """
                    午盘任务：
                    1. 先调用 get_trading_state，看上午成交、当前持仓和最新报价。
                    2. 判断未成交项和止损止盈是否要调整；需要改就调用 save_trade_plan
                       生成新版本，不能新增早盘计划里没有的 BUY 代码。
                    3. 必须调用 save_trading_journal 写一篇复盘。
                    """;
            case CLOSE -> """
                    收盘任务：
                    1. 先调用 get_trading_state，看当日成交、收盘价和计划执行情况。
                    2. 只调用 save_trading_journal，不产生新计划，不下单。
                    3. 复盘至少写：今日盈亏、每笔成交的理由是否成立、计划哪里错了、
                       明天要注意什么。
                    4. 再把今天可复用的教训合并进 save_trading_experience：先看上面的旧经验，
                       同一教训只留一条，过时结论改写而不是追加，不照抄复盘原文。
                    """;
        };
    }

    private static String yuan(long cents) {
        return String.format(Locale.ROOT, "%.2f", cents / 100.0);
    }
}
