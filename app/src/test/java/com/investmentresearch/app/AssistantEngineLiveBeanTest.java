package com.investmentresearch.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.investmentresearch.app.config.AssistantConfig;
import com.investmentresearch.core.agent.AgentEngine;
import com.investmentresearch.core.agent.AssistantEngine;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 有 key 时必须真的建出引擎；缺 key 兜底不能被当成正常路径。 */
@SpringBootTest(
        classes = Application.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "app.api-token=test-token",
            // 占位值：AgentFactory 只组装，不发请求，所以离线可跑
            "DEEPSEEK_API_KEY=sk-not-a-real-key",
            // surefire 的工作目录是模块目录 app/，skills 在仓库根
            "APP_SKILLS_DIR=../skills",
            "spring.web.resources.static-locations=classpath:/test-web/",
            "management.server.port=0"
        })
class AssistantEngineLiveBeanTest {
    private static final String DB = "target/livebean-" + UUID.randomUUID() + ".db";

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:file:" + DB + "?busy_timeout=5000");
    }

    @Autowired AssistantEngine engine;

    @Test
    void a_present_key_builds_the_real_engine() {
        assertThat(engine).isInstanceOf(AgentEngine.class);
        assertThat(engine).isNotInstanceOf(AssistantConfig.UnavailableEngine.class);
    }
}
