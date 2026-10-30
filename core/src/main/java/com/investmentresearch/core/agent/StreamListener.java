package com.investmentresearch.core.agent;

/** Callbacks for one streamed answer, mirroring the Python SSE events. */
public interface StreamListener {
    void text(String delta);

    void discard();

    void tool(ToolEvent event);
}
