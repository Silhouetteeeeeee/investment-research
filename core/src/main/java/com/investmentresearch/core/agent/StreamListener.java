package com.investmentresearch.core.agent;

/** Callbacks for one streamed answer, mirroring the Python SSE events. */
public interface StreamListener {
    void text(String delta);

    void discard();

    void tool(ToolEvent event);

    /** 思考增量；聊天页不需要，交易页用它显示完整推理过程。 */
    default void thinking(String delta) {}
}
