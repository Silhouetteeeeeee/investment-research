package com.investmentresearch.app.api;

import com.investmentresearch.core.port.KlinePort;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 会话旁的日 K 面板：只读接口，不经过模型。 */
@RestController
public class StockChartController {
    private static final int DEFAULT_DAYS = 120;
    private static final int MIN_DAYS = 20;
    private static final int MAX_DAYS = 500;

    private final KlinePort kline;

    public StockChartController(KlinePort kline) {
        this.kline = kline;
    }

    @GetMapping("/api/stocks/{code}/kline")
    public ResponseEntity<Map<String, Object>> kline(
            @PathVariable("code") String code,
            @RequestParam(value = "days", defaultValue = "120") String days) {
        if (!code.matches("\\d{6}")) {
            return error(404, "unknown code");
        }
        int limit = Math.min(MAX_DAYS, Math.max(MIN_DAYS, parseInt(days, DEFAULT_DAYS)));
        try {
            return ResponseEntity.ok(
                    Map.of("code", code, "days", limit, "bars", kline.daily(code, limit)));
        } catch (RuntimeException error) {
            String message = error.getMessage() == null ? "未知错误" : error.getMessage();
            return error(502, "K线取数失败：" + message);
        }
    }

    private static int parseInt(String raw, int fallback) {
        try {
            return Integer.parseInt(raw == null ? "" : raw.trim());
        } catch (NumberFormatException error) {
            return fallback;
        }
    }

    private static ResponseEntity<Map<String, Object>> error(int status, String message) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("error", message));
    }
}
