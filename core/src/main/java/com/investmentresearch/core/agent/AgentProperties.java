package com.investmentresearch.core.agent;

import java.nio.file.Path;

/** Wiring for the assistant engine; defaults match the Python deployment. */
public record AgentProperties(
        String model,
        Path skillsDir,
        Path dataDir,
        int maxTurns,
        String promptResource,
        boolean fullTrace,
        int compactionTokens) {

    public static final String DEFAULT_MODEL = "deepseek:deepseek-v4-flash";
    public static final int DEFAULT_MAX_TURNS = 10;
    public static final String DEFAULT_PROMPT = "/prompts/system.md";
    public static final int DEFAULT_COMPACTION_TOKENS = 80_000;

    public AgentProperties(String model, Path skillsDir, Path dataDir, int maxTurns) {
        this(model, skillsDir, dataDir, maxTurns, DEFAULT_PROMPT, false,
                DEFAULT_COMPACTION_TOKENS);
    }

    public AgentProperties(
            String model,
            Path skillsDir,
            Path dataDir,
            int maxTurns,
            String promptResource,
            boolean fullTrace) {
        this(model, skillsDir, dataDir, maxTurns, promptResource, fullTrace,
                DEFAULT_COMPACTION_TOKENS);
    }

    public AgentProperties {
        model = model == null || model.isBlank() ? DEFAULT_MODEL : model;
        skillsDir = skillsDir == null ? Path.of("skills") : skillsDir;
        dataDir = dataDir == null ? Path.of("data") : dataDir;
        maxTurns = maxTurns <= 0 ? DEFAULT_MAX_TURNS : maxTurns;
        // 交易引擎换用 /prompts/trading.md，但工具 schema 与循环都复用本类
        promptResource =
                promptResource == null || promptResource.isBlank()
                        ? DEFAULT_PROMPT
                        : promptResource;
        compactionTokens =
                compactionTokens <= 0 ? DEFAULT_COMPACTION_TOKENS : compactionTokens;
    }

    public static AgentProperties defaults() {
        return new AgentProperties(null, null, null, 0);
    }
}
