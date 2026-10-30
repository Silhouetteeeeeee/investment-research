package com.investmentresearch.data.adapter;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.investmentresearch.core.domain.StockRef;
import com.investmentresearch.core.port.CompanyDirectory;
import com.investmentresearch.data.persistence.StockEntity;
import com.investmentresearch.data.persistence.StockMapper;
import com.investmentresearch.data.sync.StockMasterSync;
import com.investmentresearch.data.upstream.UpstreamException;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** 本地 stock 表查询；表空时在首次查询内惰性抓一次全 A 列表。 */
@Repository
public class StockDirectoryAdapter implements CompanyDirectory {
    static final long FAILURE_COOLDOWN_MS = 60_000;

    private final StockMapper stockMapper;
    private final StockMasterSync sync;
    private volatile long lastFailureAt;

    public StockDirectoryAdapter(StockMapper stockMapper, StockMasterSync sync) {
        this.stockMapper = stockMapper;
        this.sync = sync;
    }

    @Override
    public Optional<StockRef> byCode(String code) {
        ensureLoaded();
        String normalized = normalizeCode(code);
        if (normalized.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(stockMapper.selectById(normalized)).map(StockDirectoryAdapter::toRef);
    }

    @Override
    public Optional<StockRef> byName(String name) {
        ensureLoaded();
        return Optional.ofNullable(
                        stockMapper.selectOne(
                                Wrappers.<StockEntity>query().eq("name", name).last("LIMIT 1")))
                .map(StockDirectoryAdapter::toRef);
    }

    @Override
    public List<StockRef> all() {
        ensureLoaded();
        return stockMapper.selectList(null).stream().map(StockDirectoryAdapter::toRef).toList();
    }

    /** 只加载一次；上游不可用时保持空表，让解析走“找不到”而不是把异常抛给调用方。 */
    private void ensureLoaded() {
        if (stockMapper.selectCount(null) > 0 || inFailureCooldown()) {
            return;
        }
        synchronized (this) {
            if (stockMapper.selectCount(null) > 0 || inFailureCooldown()) {
                return;
            }
            try {
                sync.refresh();
                lastFailureAt = 0;
            } catch (UpstreamException ignored) {
                // 上游失败保持空表；冷却 60s 后再试，避免每次解析都等一次超时
                lastFailureAt = System.currentTimeMillis();
            }
        }
    }

    private boolean inFailureCooldown() {
        return System.currentTimeMillis() - lastFailureAt < FAILURE_COOLDOWN_MS;
    }

    private static StockRef toRef(StockEntity row) {
        String name = row.getName();
        return new StockRef(row.getCode(), name == null || name.isEmpty() ? row.getCode() : name);
    }

    /** Mirrors {@code stock/db_service.py:normalize_stock_code}. */
    private static String normalizeCode(String code) {
        if (code == null) {
            return "";
        }
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < code.length(); i++) {
            if (Character.isDigit(code.charAt(i))) {
                digits.append(code.charAt(i));
            }
        }
        while (digits.length() > 0 && digits.length() < 6) {
            digits.insert(0, '0');
        }
        return digits.toString();
    }
}
