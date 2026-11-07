package com.investmentresearch.app.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.investmentresearch.app.trading.TradingBroadcaster;
import com.investmentresearch.core.trading.TradingView;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@WebMvcTest(controllers = TradingController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(TradingBroadcaster.class)
class TradingControllerTest {
    @Autowired MockMvc mockMvc;
    @Autowired TradingBroadcaster broadcaster;
    @MockitoBean TradingView view;

    @Test
    void state_returns_view_json() throws Exception {
        when(view.state("2026-11-07"))
                .thenReturn(Map.of("date", "2026-11-07", "today_status", "TRADING"));

        mockMvc.perform(get("/api/trading/state").param("date", "2026-11-07"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.date").value("2026-11-07"))
                .andExpect(jsonPath("$.today_status").value("TRADING"));
    }

    @Test
    void dates_and_equity_are_lists() throws Exception {
        when(view.dates(30)).thenReturn(List.of("2026-11-07"));
        when(view.equitySeries()).thenReturn(List.of(Map.of("tradeDate", "2026-11-07")));

        mockMvc.perform(get("/api/trading/dates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dates[0]").value("2026-11-07"));
        mockMvc.perform(get("/api/trading/equity"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].tradeDate").value("2026-11-07"));
    }

    @Test
    void unknown_phase_id_is_404() throws Exception {
        when(view.phase(9)).thenThrow(new IllegalArgumentException("未知阶段：9"));

        mockMvc.perform(get("/api/trading/phases/9")).andExpect(status().isNotFound());
    }

    @Test
    void stream_is_sse_and_receives_published_events() throws Exception {
        MvcResult result =
                mockMvc.perform(get("/api/trading/stream").accept(MediaType.TEXT_EVENT_STREAM))
                        .andExpect(request().asyncStarted())
                        .andReturn();

        broadcaster.publish(Map.of("type", "heartbeat"));

        assertThat(result.getResponse().getContentType())
                .isNotNull()
                .satisfies(
                        type ->
                                assertThat(
                                                MediaType.parseMediaType(type)
                                                        .isCompatibleWith(
                                                                MediaType.TEXT_EVENT_STREAM))
                                        .isTrue());
        assertThat(result.getResponse().getContentAsString())
                .contains("data:")
                .contains("heartbeat");
    }
}
