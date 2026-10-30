package com.investmentresearch.core.service;

import java.util.Map;

/** The transport seam under the ask service; the app module adapts it to SSE. */
public interface EventSink {
    void send(Map<String, Object> event);

    void complete();
}
