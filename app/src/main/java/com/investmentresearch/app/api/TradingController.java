package com.investmentresearch.app.api;

import com.investmentresearch.app.trading.TradingBroadcaster;
import com.investmentresearch.core.trading.TradingView;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 交易页只读接口与实时事件流；业务都在 {@link TradingView} 和 {@link TradingBroadcaster}。 */
@RestController
@RequestMapping("/api/trading")
public class TradingController {
    private static final int DATE_LIMIT = 30;
    private static final int JOURNAL_LIMIT = 100;

    private final TradingView view;
    private final TradingBroadcaster broadcaster;

    public TradingController(TradingView view, TradingBroadcaster broadcaster) {
        this.view = view;
        this.broadcaster = broadcaster;
    }

    @GetMapping("/state")
    public Map<String, Object> state(@RequestParam(name = "date", required = false) String date) {
        return view.state(date);
    }

    @GetMapping("/dates")
    public Map<String, Object> dates() {
        return Map.of("dates", view.dates(DATE_LIMIT));
    }

    @GetMapping("/equity")
    public List<Map<String, Object>> equity() {
        return view.equitySeries();
    }

    /** Agent 自己维护的长期经验文档；空态返回空串与 null 时间。 */
    @GetMapping("/experience")
    public Map<String, Object> experience() {
        return view.experience();
    }

    /** 复盘经验档案：按日期倒序，默认最近 100 篇。 */
    @GetMapping("/journals")
    public Map<String, Object> journals(
            @RequestParam(name = "limit", required = false) Integer limit) {
        return Map.of("journals", view.journals(limit == null ? JOURNAL_LIMIT : limit));
    }

    @GetMapping("/phases/{id}")
    public Map<String, Object> phase(@PathVariable("id") long id) {
        return view.phase(id);
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        return broadcaster.subscribe();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> notFound(IllegalArgumentException error) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", error.getMessage()));
    }
}
