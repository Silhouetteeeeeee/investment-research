package com.investmentresearch.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.investmentresearch.core.agent.AskResult;
import com.investmentresearch.core.agent.AssistantEngine;
import com.investmentresearch.core.agent.StreamListener;
import com.investmentresearch.core.domain.ToolSchema;
import com.investmentresearch.core.port.CompanyDirectory;
import com.investmentresearch.core.port.ConversationRepository;
import com.investmentresearch.core.port.RunStore;
import com.investmentresearch.core.run.FileRunStore;
import com.investmentresearch.core.service.AskService;
import com.investmentresearch.core.service.ContextMeter;
import com.investmentresearch.core.service.QuestionRouter;
import com.investmentresearch.core.tool.ToolRegistry;
import com.investmentresearch.data.adapter.StockDirectoryAdapter;
import com.investmentresearch.data.persistence.ConversationRepositoryImpl;
import com.investmentresearch.data.persistence.StockEntity;
import com.investmentresearch.data.persistence.StockMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** The whole graph the app's read APIs and /api/ask expect must actually exist. */
@SpringBootTest(
        classes = Application.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "app.api-token=test-token",
            "spring.web.resources.static-locations=classpath:/test-web/",
            "management.server.port=0"
        })
class AssistantWiringTest {
    private static final String DB = "target/wiring-" + UUID.randomUUID() + ".db";

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:file:" + DB + "?busy_timeout=5000");
    }

    @Autowired ApplicationContext context;
    @Autowired StockMapper stockMapper;
    @Autowired TestRestTemplate rest;

    // Replacing the engine keeps the wiring test offline and independent of any local API key.
    @MockitoBean AssistantEngine engine;

    @BeforeEach
    void seedStock() {
        // A non-empty stock table stops StockDirectoryAdapter from calling the network.
        if (stockMapper.selectById("600519") == null) {
            StockEntity row = new StockEntity();
            row.setCode("600519");
            row.setName("贵州茅台");
            row.setRefreshedAt("2026-10-30 00:00:00");
            stockMapper.insert(row);
        }
    }

    @Test
    void beans_exist_and_ask_reaches_the_real_ask_service() {
        assertThat(context.getBean(ConversationRepository.class))
                .isInstanceOf(ConversationRepositoryImpl.class);
        assertThat(context.getBean(RunStore.class)).isInstanceOf(FileRunStore.class);
        assertThat(context.getBean(CompanyDirectory.class))
                .isInstanceOf(StockDirectoryAdapter.class);
        assertThat(context.getBean(ToolRegistry.class).schemas())
                .extracting(ToolSchema::name)
                .containsExactly(
                        "resolve_company",
                        "get_company_profile",
                        "get_finance",
                        "get_financial_history",
                        "get_segments",
                        "get_management",
                        "get_shareholders",
                        "get_peers",
                        "get_dividends",
                        "get_insider_trades",
                        "get_buybacks",
                        "get_lockup_expiries",
                        "get_pledges",
                        "get_earnings_forecasts",
                        "get_institutional_research",
                        "get_research_notes",
                        "get_analyst_views",
                        "get_announcements",
                        "get_valuation",
                        "calc_valuation",
                        "get_market_overview",
                        "get_news_events",
                        "get_sector_rotation",
                        "screen_candidates",
                        "build_strategy_plan",
                        "save_daily_judgment",
                        "review_research_reports",
                        "list_research_memory",
                        "read_research_memory",
                        "append_research_record",
                        "update_research_record",
                        "record_conclusion_change");
        assertThat(context.getBean(AskService.class)).isNotNull();
        assertThat(context.getBean(QuestionRouter.class)).isNotNull();
        assertThat(context.getBean(ContextMeter.class)).isNotNull();

        when(engine.ask(anyLong(), anyString(), anyString(), anyString(), any()))
                .thenReturn(new AskResult("测试回答", List.of(), List.of(), List.of(), null, List.of()));

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("test-token");
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.TEXT_EVENT_STREAM));
        ResponseEntity<String> response =
                rest.postForEntity(
                        "/api/ask",
                        new HttpEntity<>("{\"question\":\"贵州茅台怎么样\"}", headers),
                        String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType())
                .isNotNull()
                .satisfies(
                        type ->
                                assertThat(type.isCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                                        .isTrue());
        assertThat(response.getBody()).contains("\"type\":\"state\"").contains("\"type\":\"done\"");
        verify(engine)
                .ask(
                        eq(1L),
                        eq("600519"),
                        eq("贵州茅台"),
                        eq("贵州茅台怎么样"),
                        any(StreamListener.class));
    }
}
