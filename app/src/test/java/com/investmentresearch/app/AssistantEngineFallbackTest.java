package com.investmentresearch.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.investmentresearch.app.config.AssistantConfig;
import com.investmentresearch.core.agent.AssistantEngine;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 缺 key 时容器必须照常起来，并把「助手不可用」的原因留给 /api/ask。 */
@SpringBootTest(
        classes = Application.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "app.api-token=test-token",
            // 显式置空：本机真的设了 DEEPSEEK_API_KEY 也不能让这条测试变绿
            "DEEPSEEK_API_KEY=",
            "spring.web.resources.static-locations=classpath:/test-web/",
            "management.server.port=0"
        })
class AssistantEngineFallbackTest {
    private static final String DB = "target/fallback-" + UUID.randomUUID() + ".db";

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:file:" + DB + "?busy_timeout=5000");
    }

    @Autowired AssistantEngine engine;

    @Test
    void missing_key_yields_the_unavailable_engine() {
        assertThat(engine).isInstanceOf(AssistantConfig.UnavailableEngine.class);
        assertThat(((AssistantConfig.UnavailableEngine) engine).reason())
                .isEqualTo("请先设置环境变量 DEEPSEEK_API_KEY");
    }
}
