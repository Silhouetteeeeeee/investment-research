package com.investmentresearch.core.agent;

/** The engine seam used by the ask service; one instance serves every conversation. */
public interface AssistantEngine {
    AskResult ask(
            long conversationId, String code, String name, String question, StreamListener listener);

    void deleteSession(long conversationId);
}
