package com.investmentresearch.data.adapter.eastmoney;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.investmentresearch.data.persistence.ValHistoryEntity;
import com.investmentresearch.data.persistence.ValHistoryMapper;
import com.investmentresearch.data.persistence.ValPeerEntity;
import com.investmentresearch.data.persistence.ValPeerMapper;
import com.investmentresearch.data.upstream.UpstreamClient;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

/**
 * 估值取数/缓存，照抄 {@code stock/valuation.py:ensure_valuation}：历史与同行业快照各自 6 小时缓存，
 * 抓取失败但本地有缓存时降级并标记 stale。今日尚无估值时以最近一个交易日为准。
 */
@Repository
class ValuationSupport {
    static final String REPORT_NAME = "RPT_VALUEANALYSIS_DET";
    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ValHistoryMapper historyMapper;
    private final ValPeerMapper peerMapper;
    private final UpstreamClient upstreamClient;
    private final String endpoint;
    private final Clock clock;

    @Autowired
    ValuationSupport(
            ValHistoryMapper historyMapper,
            ValPeerMapper peerMapper,
            UpstreamClient upstreamClient,
            @Value("${app.datacenter-url:https://datacenter-web.eastmoney.com/api/data/v1/get}")
                    String endpoint) {
        this(historyMapper, peerMapper, upstreamClient, endpoint, Clock.systemDefaultZone());
    }

    ValuationSupport(
            ValHistoryMapper historyMapper,
            ValPeerMapper peerMapper,
            UpstreamClient upstreamClient,
            String endpoint,
            Clock clock) {
        this.historyMapper = historyMapper;
        this.peerMapper = peerMapper;
        this.upstreamClient = upstreamClient;
        this.endpoint = endpoint;
        this.clock = clock;
    }

    record Snapshot(
            List<ValHistoryEntity> history,
            List<ValPeerEntity> peers,
            boolean stale,
            String peersError) {}

    Snapshot ensure(String code) {
        History history = ensureHistory(code);
        if (history.rows().isEmpty()) {
            throw new EastmoneySupport.EastmoneyException(code + "：没有取到估值数据");
        }
        List<ValHistoryEntity> rows = untilToday(history.rows());
        ValHistoryEntity latest = rows.get(rows.size() - 1);

        List<ValPeerEntity> peers = List.of();
        String peersError = null;
        String boardCode = latest.getBoardCode();
        if (boardCode != null && !boardCode.isBlank()) {
            try {
                peers = ensurePeers(boardCode, latest.getTradeDate());
            } catch (EastmoneySupport.EastmoneyException error) {
                // 同业对比是锦上添花，取不到就降级到本地缓存
                peers = cachedPeers(boardCode, latest.getTradeDate());
                peersError = error.getMessage();
            }
        }
        return new Snapshot(rows, peers, history.stale(), peersError);
    }

    private record History(List<ValHistoryEntity> rows, boolean stale) {}

    private History ensureHistory(String code) {
        List<ValHistoryEntity> cached = cachedHistory(code);
        if (fresh(cached.stream().map(ValHistoryEntity::getFetchedAt).toList())) {
            return new History(cached, false);
        }
        try {
            List<ValHistoryEntity> rows = fetchHistory(code);
            if (!rows.isEmpty()) {
                saveHistory(code, rows);
                return new History(cachedHistory(code), false);
            }
            return new History(cached, !cached.isEmpty());
        } catch (EastmoneySupport.EastmoneyException error) {
            if (!cached.isEmpty()) {
                return new History(cached, true);
            }
            throw error;
        }
    }

    private List<ValPeerEntity> ensurePeers(String boardCode, String tradeDate) {
        List<ValPeerEntity> cached = cachedPeers(boardCode, tradeDate);
        if (fresh(cached.stream().map(ValPeerEntity::getFetchedAt).toList())) {
            return cached;
        }
        List<ValPeerEntity> rows = fetchPeers(boardCode, tradeDate);
        if (rows.isEmpty()) {
            return cached;
        }
        savePeers(boardCode, tradeDate, rows);
        return cachedPeers(boardCode, tradeDate);
    }

    /** 估值只到最近一个交易日为止：今天还没有收盘数据时自然回落到上一交易日。 */
    private List<ValHistoryEntity> untilToday(List<ValHistoryEntity> rows) {
        String today = LocalDate.now(clock).toString();
        List<ValHistoryEntity> filtered =
                rows.stream()
                        .filter(row -> row.getTradeDate() != null && row.getTradeDate().compareTo(today) <= 0)
                        .toList();
        return filtered.isEmpty() ? rows : filtered;
    }

    private List<ValHistoryEntity> fetchHistory(String code) {
        List<JsonNode> raw =
                EastmoneySupport.fetchRows(
                        upstreamClient,
                        endpoint,
                        REPORT_NAME,
                        "(SECURITY_CODE=\"" + code + "\")",
                        "-1:TRADE_DATE",
                        3000,
                        "估值");
        String now = EastmoneySupport.timestamp(clock);
        List<ValHistoryEntity> rows = new ArrayList<>();
        for (JsonNode item : raw) {
            String tradeDate = EastmoneySupport.datePart(item.get("TRADE_DATE"));
            if (tradeDate == null) {
                continue;
            }
            ValHistoryEntity row = new ValHistoryEntity();
            row.setCode(code);
            row.setTradeDate(tradeDate);
            row.setName(EastmoneySupport.text(item.get("SECURITY_NAME_ABBR")));
            row.setPeTtm(EastmoneySupport.num(item.get("PE_TTM")));
            row.setPbMrq(EastmoneySupport.num(item.get("PB_MRQ")));
            row.setTotalMv(EastmoneySupport.num(item.get("TOTAL_MARKET_CAP")));
            row.setClose(EastmoneySupport.num(item.get("CLOSE_PRICE")));
            row.setBoardCode(EastmoneySupport.text(item.get("BOARD_CODE")));
            row.setBoardName(EastmoneySupport.text(item.get("BOARD_NAME")));
            row.setFetchedAt(now);
            rows.add(row);
        }
        return rows;
    }

    private List<ValPeerEntity> fetchPeers(String boardCode, String tradeDate) {
        List<JsonNode> raw =
                EastmoneySupport.fetchRows(
                        upstreamClient,
                        endpoint,
                        REPORT_NAME,
                        "(BOARD_CODE=\"" + boardCode + "\")(TRADE_DATE='" + tradeDate + "')",
                        "-1:TOTAL_MARKET_CAP",
                        500,
                        "估值");
        String now = EastmoneySupport.timestamp(clock);
        List<ValPeerEntity> rows = new ArrayList<>();
        for (JsonNode item : raw) {
            String code = EastmoneySupport.text(item.get("SECURITY_CODE"));
            if (code == null) {
                continue;
            }
            ValPeerEntity row = new ValPeerEntity();
            row.setBoardCode(boardCode);
            row.setTradeDate(tradeDate);
            row.setCode(code);
            row.setName(EastmoneySupport.text(item.get("SECURITY_NAME_ABBR")));
            row.setPeTtm(EastmoneySupport.num(item.get("PE_TTM")));
            row.setPbMrq(EastmoneySupport.num(item.get("PB_MRQ")));
            row.setTotalMv(EastmoneySupport.num(item.get("TOTAL_MARKET_CAP")));
            row.setFetchedAt(now);
            rows.add(row);
        }
        return rows;
    }

    private List<ValHistoryEntity> cachedHistory(String code) {
        return historyMapper.selectList(
                Wrappers.<ValHistoryEntity>query()
                        .eq("code", code)
                        .orderByAsc("trade_date"));
    }

    private List<ValPeerEntity> cachedPeers(String boardCode, String tradeDate) {
        return peerMapper.selectList(
                Wrappers.<ValPeerEntity>query()
                        .eq("board_code", boardCode)
                        .eq("trade_date", tradeDate)
                        .orderByDesc("total_mv"));
    }

    private void saveHistory(String code, List<ValHistoryEntity> rows) {
        historyMapper.delete(Wrappers.<ValHistoryEntity>query().eq("code", code));
        for (ValHistoryEntity row : rows) {
            historyMapper.insert(row);
        }
    }

    private void savePeers(String boardCode, String tradeDate, List<ValPeerEntity> rows) {
        peerMapper.delete(
                Wrappers.<ValPeerEntity>query()
                        .eq("board_code", boardCode)
                        .eq("trade_date", tradeDate));
        for (ValPeerEntity row : rows) {
            peerMapper.insert(row);
        }
    }

    /** Python {@code _age_hours}：取最晚的抓取时间；没有可用时间戳时视为无限旧。 */
    private boolean fresh(List<String> stamps) {
        String newest = stamps.stream().filter(Objects::nonNull).max(String::compareTo).orElse(null);
        if (newest == null) {
            return false;
        }
        LocalDateTime fetched;
        try {
            fetched = LocalDateTime.parse(newest, TIMESTAMP);
        } catch (RuntimeException error) {
            return false;
        }
        Duration age = Duration.between(fetched, LocalDateTime.now(clock));
        return age.compareTo(Duration.ofHours((long) EastmoneySupport.MAX_AGE_HOURS)) <= 0;
    }
}
