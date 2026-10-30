package com.investmentresearch.core.agent;

import com.investmentresearch.core.tool.ToolRegistry;
import io.agentscope.core.model.ModelCreationContext;
import io.agentscope.core.model.ModelRegistry;

/** Builds the live engine. Tests inject a scripted model through {@link AgentEngine} instead. */
public final class AgentFactory {

    private AgentFactory() {}

    /** The key is passed in rather than read here so the config branch stays testable. */
    public static AssistantEngine create(
            ToolRegistry tools, AgentProperties properties, String apiKey) {
        return new AgentEngine(
                tools,
                properties,
                () ->
                        ModelRegistry.resolve(
                                properties.model(),
                                ModelCreationContext.builder().apiKey(apiKey).build()));
    }
}
