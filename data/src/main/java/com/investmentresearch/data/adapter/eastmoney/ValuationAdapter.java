package com.investmentresearch.data.adapter.eastmoney;

import com.investmentresearch.core.domain.Source;
import com.investmentresearch.core.domain.ToolResult;
import com.investmentresearch.core.port.ValuationPort;
import com.investmentresearch.data.persistence.StockMapper;
import com.investmentresearch.data.persistence.ValHistoryEntity;
import com.investmentresearch.data.persistence.ValPeerEntity;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

/** 东财估值：逐日历史分位 + 同行业估值对比，输出照抄 {@code assistant/tools.py:_valuation}。 */
@Repository
public class ValuationAdapter implements ValuationPort {
    static final int YEARS = 5;
    static final int PEER_LIMIT = 8;
    static final int SUMMARY_PEERS = 5;

    private final ValuationSupport support;
    private final StockMapper stockMapper;

    @Autowired
    public ValuationAdapter(ValuationSupport support, StockMapper stockMapper) {
        this.support = support;
        this.stockMapper = stockMapper;
    }

    @Override
    public ToolResult valuation(String rawCode) {
        String code = EastmoneySupport.normalize(rawCode);
        if (code.isEmpty()) {
            return ToolResult.failure(rawCode, "请输入股票代码");
        }
        try {
            ValuationSupport.Snapshot snapshot = support.ensure(code);
            if (snapshot.history().isEmpty()) {
                return ToolResult.failure(code, code + "：没有取到估值数据");
            }
            return assemble(code, snapshot);
        } catch (RuntimeException error) {
            // 端口不抛异常：上游、读库或组装出的意外错误同样折成失败
            return ToolResult.failure(code, error.getMessage());
        }
    }

    private ToolResult assemble(String code, ValuationSupport.Snapshot snapshot) {
        List<ValHistoryEntity> history = snapshot.history();
        ValHistoryEntity latest = history.get(history.size() - 1);
        String asOf = latest.getTradeDate();

        String name = stockMapper.selectNameByCode(code);
        if (name == null) {
            name = latest.getName();
        }

        List<ValHistoryEntity> window = window(history, asOf);
        List<Double> peSeries = new ArrayList<>();
        List<Double> pbSeries = new ArrayList<>();
        for (ValHistoryEntity row : window) {
            if (EastmoneySupport.positive(row.getPeTtm())) {
                peSeries.add(row.getPeTtm());
            }
            if (EastmoneySupport.positive(row.getPbMrq())) {
                pbSeries.add(row.getPbMrq());
            }
        }
        Stat pe = stats(peSeries, latest.getPeTtm());
        Stat pb = stats(pbSeries, latest.getPbMrq());
        Industry industry = industry(snapshot.peers(), latest);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("pe_ttm", pe.value());
        // valuation 的分位本来就是 0~100，不能再乘 100
        data.put("pe_percentile", EastmoneySupport.pct(pe.percentile()));
        data.put("pb_mrq", pb.value());
        data.put("pb_percentile", EastmoneySupport.pct(pb.percentile()));
        data.put("years", YEARS);
        data.put("price", latest.getClose());
        data.put("market_cap_yi", EastmoneySupport.yi(latest.getTotalMv()));
        Map<String, Object> industryData = new LinkedHashMap<>();
        industryData.put("board", latest.getBoardName());
        industryData.put("count", industry.count());
        industryData.put("median_pe", industry.medianPe());
        industryData.put("median_pb", industry.medianPb());
        industryData.put("rank_pe", industry.rankPe());
        industryData.put("rank_total", industry.rankTotal());
        data.put("industry", industryData);
        List<Map<String, Object>> peers = new ArrayList<>();
        for (ValPeerEntity peer : industry.peers().subList(0, Math.min(SUMMARY_PEERS, industry.peers().size()))) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", peer.getName());
            item.put("pe_ttm", peer.getPeTtm());
            item.put("pb_mrq", peer.getPbMrq());
            peers.add(item);
        }
        data.put("peers", peers);

        Source source =
                new Source(
                        "valuation:" + code + ":" + asOf,
                        "估值",
                        (name == null ? "None" : name) + " " + asOf + " 估值",
                        asOf,
                        latest.getFetchedAt() == null ? "" : latest.getFetchedAt());
        return ToolResult.ok(code, asOf, List.of(source), data);
    }

    /** 最近 N 年的数据；cutoff 之后没有行时保留最新一行。 */
    private static List<ValHistoryEntity> window(List<ValHistoryEntity> rows, String latestDate) {
        LocalDate cutoff;
        try {
            cutoff = LocalDate.parse(latestDate).minusDays(365L * YEARS);
        } catch (DateTimeParseException error) {
            return rows;
        }
        String text = cutoff.toString();
        List<ValHistoryEntity> windowed =
                rows.stream()
                        .filter(row -> row.getTradeDate() != null && row.getTradeDate().compareTo(text) >= 0)
                        .toList();
        return windowed.isEmpty() ? rows.subList(rows.size() - 1, rows.size()) : windowed;
    }

    private record Stat(Double value, Double median, Double percentile, int samples) {}

    /** 照抄 {@code stock/valuation.py:_stats}。 */
    private static Stat stats(List<Double> values, Double current) {
        Double percentile = null;
        if (EastmoneySupport.positive(current) && !values.isEmpty()) {
            percentile =
                    values.stream().filter(value -> value <= current).count()
                            / (double) values.size()
                            * 100.0;
        }
        return new Stat(current, EastmoneySupport.median(values), percentile, values.size());
    }

    private record Industry(
            int count,
            Double medianPe,
            Double medianPb,
            Integer rankPe,
            int rankTotal,
            List<ValPeerEntity> peers) {}

    /** 照抄 {@code stock/valuation.py:_industry}。 */
    private static Industry industry(List<ValPeerEntity> peers, ValHistoryEntity latest) {
        List<Double> peValues = new ArrayList<>();
        List<Double> pbValues = new ArrayList<>();
        for (ValPeerEntity peer : peers) {
            if (EastmoneySupport.positive(peer.getPeTtm())) {
                peValues.add(peer.getPeTtm());
            }
            if (EastmoneySupport.positive(peer.getPbMrq())) {
                pbValues.add(peer.getPbMrq());
            }
        }
        Double ownPe = latest.getPeTtm();
        Integer rank = null;
        if (EastmoneySupport.positive(ownPe) && !peValues.isEmpty()) {
            // 排名只在盈利公司之间比，亏损公司的 PE 没有大小意义
            rank =
                    1
                            + (int)
                                    peValues.stream().filter(value -> value < ownPe).count();
        }
        List<ValPeerEntity> ordered = new ArrayList<>(peers);
        ordered.sort(
                Comparator.comparingDouble(
                                (ValPeerEntity peer) ->
                                        peer.getTotalMv() == null ? 0.0 : peer.getTotalMv())
                        .reversed());
        return new Industry(
                peers.size(),
                EastmoneySupport.median(peValues),
                EastmoneySupport.median(pbValues),
                rank,
                peValues.size(),
                ordered.subList(0, Math.min(PEER_LIMIT, ordered.size())));
    }
}
