package com.investmentresearch.app.trading;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.investmentresearch.app.api.SseWriter;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 交易页的 SSE 广播；没有订阅者时事件直接丢弃，不影响落库。 */
public final class TradingBroadcaster {
    private static final long TIMEOUT_MS = 30 * 60 * 1000L;

    private final ObjectMapper json;
    private final CopyOnWriteArrayList<SseWriter> writers = new CopyOnWriteArrayList<>();

    public TradingBroadcaster(ObjectMapper json) {
        this.json = json;
    }

    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        SseWriter writer = new SseWriter(emitter, json);
        writers.add(writer);
        // 立刻发一帧，把响应头刷出去；否则客户端要等到下一次心跳才算连上
        writer.send(Map.of("type", "heartbeat"));
        emitter.onCompletion(() -> writers.remove(writer));
        emitter.onTimeout(
                () -> {
                    writers.remove(writer);
                    emitter.complete();
                });
        emitter.onError(error -> writers.remove(writer));
        return emitter;
    }

    public void publish(Map<String, Object> event) {
        for (SseWriter writer : writers) {
            writer.send(event);
        }
    }

    /** 心跳防止中间代理把空闲连接掐掉。 */
    @Scheduled(fixedDelay = 20_000)
    public void heartbeat() {
        publish(Map.of("type", "heartbeat"));
    }
}
