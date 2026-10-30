package com.investmentresearch.core.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import reactor.core.publisher.Flux;

/** Fake model: returns pre-scripted replies in order and records the messages it was given. */
public final class ScriptedModel implements Model {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final Deque<ChatResponse> replies = new ArrayDeque<>();
    private final List<List<Msg>> calls = new ArrayList<>();

    public ScriptedModel(ChatResponse... replies) {
        for (ChatResponse reply : replies) {
            this.replies.add(reply);
        }
    }

    public static ChatResponse text(String text) {
        return reply(TextBlock.builder().text(text).build(), "stop");
    }

    public static ChatResponse toolCall(String name, Map<String, Object> arguments) {
        Map<String, Object> input = arguments == null ? Map.of() : arguments;
        String content;
        try {
            content = JSON.writeValueAsString(input);
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException(error);
        }
        return reply(
                ToolUseBlock.builder()
                        .id("call_" + name + "_" + UUID.randomUUID().toString().substring(0, 8))
                        .name(name)
                        .input(input)
                        // AgentScope 用 content 做参数校验，input 只负责交给工具
                        .content(content)
                        .build(),
                "tool_calls");
    }

    private static ChatResponse reply(ContentBlock block, String finishReason) {
        return ChatResponse.builder()
                .id("reply")
                .content(List.of(block))
                .finishReason(finishReason)
                .build();
    }

    @Override
    public Flux<ChatResponse> stream(
            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        calls.add(List.copyOf(messages));
        ChatResponse reply = replies.poll();
        if (reply == null) {
            throw new IllegalStateException("ScriptedModel ran out of replies");
        }
        return Flux.just(reply);
    }

    @Override
    public String getModelName() {
        return "scripted";
    }

    List<List<Msg>> calls() {
        return calls;
    }
}
