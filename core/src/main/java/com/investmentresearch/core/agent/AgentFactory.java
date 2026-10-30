package com.investmentresearch.core.agent;

import com.investmentresearch.core.tool.ToolRegistry;
import io.agentscope.core.model.ModelCreationContext;
import io.agentscope.core.model.ModelRegistry;

/** Builds the live engine. Tests inject a scripted model through {@link AgentEngine} instead. */
public final class AgentFactory {

    private AgentFactory() {}

    public static AssistantEngine create(ToolRegistry tools, AgentProperties properties) {
        String apiKey = System.getenv("DEEPSEEK_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("请先设置环境变量 DEEPSEEK_API_KEY");
        }
        return new AgentEngine(
                tools,
                properties,
                () ->
                        ModelRegistry.resolve(
                                properties.model(),
                                ModelCreationContext.builder().apiKey(apiKey).build()));
    }
}
