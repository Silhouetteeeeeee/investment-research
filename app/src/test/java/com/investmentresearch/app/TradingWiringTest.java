package com.investmentresearch.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.investmentresearch.app.config.AssistantConfig;
import com.investmentresearch.core.agent.AssistantEngine;
import com.investmentresearch.core.domain.ToolSchema;
import com.investmentresearch.core.tool.ToolRegistry;
import com.investmentresearch.core.trading.TradingContext;
import com.investmentresearch.core.trading.TradingService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** 交易侧的工具注册、引擎与编排 bean 必须和聊天链路一起装得起来。 */
@SpringBootTest(
        classes = Application.class,
        properties = {
            "app.api-token=test-token",
            "management.server.port=0",
            // 固定成无 key，交易引擎才是可断言的 UnavailableEngine
            "DEEPSEEK_API_KEY="
        })
class TradingWiringTest {
    private static final String DB = "target/trading-wiring-" + UUID.randomUUID() + ".db";

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:file:" + DB + "?busy_timeout=5000");
    }

    // 字段名与 bean 名一致，只替换聊天引擎，交易引擎仍是真实 bean
    @MockitoBean AssistantEngine assistantEngine;

    @Autowired
    @Qualifier("toolRegistry")
    ToolRegistry chatRegistry;

    @Autowired
    @Qualifier("tradingToolRegistry")
    ToolRegistry tradingRegistry;

    @Autowired TradingContext tradingContext;
    @Autowired TradingService tradingService;

    @Autowired
    @Qualifier("tradingEngine")
    AssistantEngine tradingEngine;

    @Test
    void trading_registry_extends_chat_tools_with_three_trading_tools() {
        // chat 注册 33 个工具（JSON 里的 load_skill 由框架提供，不在 registry 里）
        assertThat(chatRegistry.schemas()).hasSize(33);
        assertThat(tradingRegistry.schemas()).hasSize(36);
        assertThat(tradingRegistry.schemas())
                .extracting(ToolSchema::name)
                .contains("get_trading_state", "save_trade_plan", "save_trading_journal");
    }

    @Test
    void trading_context_service_and_engine_are_wired() {
        assertThat(tradingContext).isNotNull();
        assertThat(tradingService).isNotNull();
        assertThat(tradingEngine).isInstanceOf(AssistantConfig.UnavailableEngine.class);
    }
}
