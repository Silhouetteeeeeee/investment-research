package com.investmentresearch.app.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@WebMvcTest(
        controllers = AskController.class,
        excludeAutoConfiguration = UserDetailsServiceAutoConfiguration.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(AskControllerTest.DirectExecutorConfig.class)
class AskControllerTest {
    @Autowired MockMvc mvc;

    @Test
    void empty_question_returns_400() throws Exception {
        mvc.perform(
                        post("/api/ask")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"question\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error").value("empty question"));
    }

    @Test
    void ask_returns_event_stream_and_ends_with_error_event() throws Exception {
        MvcResult result =
                mvc.perform(
                                post("/api/ask")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"question\":\"你好\"}"))
                        .andExpect(request().asyncStarted())
                        .andReturn();

        mvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andExpect(header().string("Cache-Control", "no-cache"))
                .andExpect(header().string("X-Accel-Buffering", "no"))
                .andExpect(
                        content()
                                .string(
                                        "data: {\"type\":\"error\",\"text\":\"助手尚未接入模型\"}\n\n"));
    }

    // Runs the placeholder on the request thread so the test can assert the streamed body
    // deterministically; the real virtual-thread bean is exercised by AskStreamAuthTest.
    @TestConfiguration
    static class DirectExecutorConfig {
        @Bean
        Executor askExecutor() {
            return Runnable::run;
        }
    }
}
