package com.investmentresearch.core.agent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AgentPropertiesTest {

    @Test
    void chat_defaults_compact_at_80k() {
        assertThat(AgentProperties.defaults().compactionTokens()).isEqualTo(80_000);
    }

    @Test
    void compaction_threshold_is_configurable_per_engine() {
        // 交易引擎按设计用 120k：一天内多轮工具调用更长，压缩太早会丢上下文
        AgentProperties trading =
                new AgentProperties(
                        null, null, null, 14, "/prompts/trading.md", true, 120_000);
        assertThat(trading.compactionTokens()).isEqualTo(120_000);
        assertThat(trading.maxTurns()).isEqualTo(14);
    }
}
