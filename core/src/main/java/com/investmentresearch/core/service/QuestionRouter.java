package com.investmentresearch.core.service;

import com.investmentresearch.core.domain.StockRef;
import com.investmentresearch.core.port.CompanyDirectory;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Port of {@code assistant/api.py:route_question} plus the text scans in
 * {@code assistant/tools.py}: a mentioned company wins, then market questions, otherwise keep.
 */
public final class QuestionRouter {
    public static final String MARKET_CODE = "MARKET";
    public static final String MARKET_NAME = "大盘";

    private static final Pattern SIX_DIGITS = Pattern.compile("(?<!\\d)(\\d{6})(?!\\d)");

    private static final List<String> MARKET_WORDS =
            List.of(
                    "大盘", "指数", "上证", "深证", "创业板", "科创", "沪深300", "中证",
                    "两市", "A股", "a股", "市场", "行情", "板块", "涨停", "跌停",
                    "成交量", "成交额", "仓位", "操作建议", "北向", "轮动", "题材",
                    "热点", "后市", "减仓", "加仓", "轻仓", "重仓", "空仓", "满仓",
                    "情绪", "走势", "怎么走", "新闻", "政策", "事件", "海外", "美股",
                    "港股", "美联储", "降息", "降准", "关税", "消息面", "利好", "利空",
                    "影响哪些行业", "行业轮动", "强势行业", "板块强弱", "主线",
                    "哪些行业", "什么行业", "走强", "走弱", "热点能否持续",
                    "选股", "筛选", "候选股", "股票池", "值得研究", "值得关注",
                    "复盘", "命中", "超额", "准不准", "策略", "几成仓", "建仓", "持仓",
                    "止损", "止盈", "失误", "观察窗口", "存档",
                    "记录今天", "记录一下今天", "保存今天", "保存一下今天",
                    "记下来", "存下来");

    private static final List<String> ANAPHORA_WORDS = List.of("它", "该股", "这家", "该公司", "这只", "此股");

    private final CompanyDirectory directory;

    public QuestionRouter(CompanyDirectory directory) {
        this.directory = directory;
    }

    /** {@code assistant/api.py:80-89}. */
    public Route route(String currentCode, String currentName, String question) {
        StockRef mentioned = findCompanyInText(question);
        if (mentioned != null) {
            String name = mentioned.name() == null || mentioned.name().isEmpty()
                    ? mentioned.code()
                    : mentioned.name();
            return new Route(mentioned.code(), name, !Objects.equals(mentioned.code(), currentCode));
        }
        if (isMarketQuestion(question)) {
            boolean keeps =
                    currentCode != null
                            && !MARKET_CODE.equals(currentCode)
                            && refersToCompany(question);
            if (!keeps) {
                return new Route(MARKET_CODE, MARKET_NAME, !MARKET_CODE.equals(currentCode));
            }
        }
        return new Route(currentCode, currentName, false);
    }

    /** {@code assistant/tools.py:find_company_in_text}: a six-digit code first, then stock names. */
    StockRef findCompanyInText(String text) {
        String value = text == null ? "" : text.strip();
        if (value.isEmpty()) {
            return null;
        }

        Matcher match = SIX_DIGITS.matcher(value);
        if (match.find()) {
            // 只认本地股票表里的代码，避免把句子里的六位数字当成股票
            Optional<StockRef> stock = directory.byCode(match.group(1));
            if (stock.isPresent()) {
                return stock.get();
            }
        }

        Best best = null;
        for (StockRef stock : directory.all()) {
            String name = stock.name() == null ? "" : stock.name().strip();
            if (name.length() < 2) {
                continue;
            }
            int index = value.indexOf(name);
            if (index < 0) {
                continue;
            }
            // 位置优先，其次名字更长（避免「中国平安」被「平安」抢走）
            if (best == null || index < best.index || (index == best.index && name.length() > best.nameLength)) {
                best = new Best(index, name.length(), stock);
            }
        }
        return best == null ? null : best.stock;
    }

    /** {@code assistant/tools.py:refers_to_company}. */
    static boolean refersToCompany(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        for (String word : ANAPHORA_WORDS) {
            if (text.contains(word)) {
                return true;
            }
        }
        return false;
    }

    /** {@code assistant/tools.py:is_market_question}. */
    static boolean isMarketQuestion(String text) {
        if (text == null || text.strip().isEmpty()) {
            return false;
        }
        for (String word : MARKET_WORDS) {
            if (text.contains(word)) {
                return true;
            }
        }
        return false;
    }

    private record Best(int index, int nameLength, StockRef stock) {}
}
