package com.investmentresearch.app.api;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.investmentresearch.app.config.AssistantConfig;
import com.investmentresearch.core.agent.AssistantEngine;
import com.investmentresearch.core.service.AskService;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** Missing model key must answer like Python's outer except, not crash or stall the stream. */
@WebMvcTest(
        controllers = AskController.class,
        excludeAutoConfiguration = UserDetailsServiceAutoConfiguration.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(AskControllerUnavailableTest.Harness.class)
class AskControllerUnavailableTest {
    @Autowired MockMvc mvc;
    @MockitoBean AskService service;

    @Test
    void missing_model_key_answers_with_clean_unavailable_error() throws Exception {
        MvcResult started =
                mvc.perform(
                                post("/api/ask")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"question\":\"你好\"}"))
                        .andExpect(request().asyncStarted())
                        .andReturn();

        mvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andExpect(
                        content()
                                .string(
                                        "data: {\"type\":\"error\",\"text\":\"助手不可用："
                                                + "请先设置环境变量 DEEPSEEK_API_KEY\"}\n\n"));

        verifyNoInteractions(service);
    }

    @TestConfiguration
    static class Harness {
        @Bean
        Executor askExecutor() {
            return Runnable::run;
        }

        @Bean
        AssistantEngine assistantEngine() {
            return new AssistantConfig.UnavailableEngine("请先设置环境变量 DEEPSEEK_API_KEY");
        }
    }
}
