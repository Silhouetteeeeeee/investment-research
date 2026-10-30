package com.investmentresearch.core.agent;

import java.nio.file.Path;

/** Wiring for the assistant engine; defaults match the Python deployment. */
public record AgentProperties(String model, Path skillsDir, Path dataDir, int maxTurns) {

    public static final String DEFAULT_MODEL = "deepseek:deepseek-v4-flash";
    public static final int DEFAULT_MAX_TURNS = 10;

    public AgentProperties {
        model = model == null || model.isBlank() ? DEFAULT_MODEL : model;
        skillsDir = skillsDir == null ? Path.of("skills") : skillsDir;
        dataDir = dataDir == null ? Path.of("data") : dataDir;
        maxTurns = maxTurns <= 0 ? DEFAULT_MAX_TURNS : maxTurns;
    }

    public static AgentProperties defaults() {
        return new AgentProperties(null, null, null, 0);
    }
}
