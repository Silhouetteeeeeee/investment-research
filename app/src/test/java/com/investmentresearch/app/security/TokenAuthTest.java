package com.investmentresearch.app.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.investmentresearch.app.Application;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootTest(
        classes = Application.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "app.api-token=test-token",
            "spring.datasource.url=jdbc:sqlite:file:target/token-auth-test.db?busy_timeout=5000",
            "spring.web.resources.static-locations=classpath:/test-web/"
        })
@AutoConfigureMockMvc
@Import(TokenAuthTest.TestApi.class)
class TokenAuthTest {
    @Autowired MockMvc mvc;

    @Test
    void api_without_token_returns_401_json() throws Exception {
        mvc.perform(get("/api/ping"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error").value("unauthorized"));
    }

    @Test
    void api_with_wrong_token_returns_401() throws Exception {
        mvc.perform(get("/api/ping").header(HttpHeaders.AUTHORIZATION, "Bearer not-the-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void api_with_unicode_token_returns_401_not_500() throws Exception {
        mvc.perform(get("/api/ping").header(HttpHeaders.AUTHORIZATION, "Bearer 令牌🎫"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void api_with_valid_token_passes() throws Exception {
        mvc.perform(get("/api/ping").header(HttpHeaders.AUTHORIZATION, "Bearer test-token"))
                .andExpect(status().isOk())
                .andExpect(content().string("pong"));
    }

    @Test
    void health_and_index_need_no_token() throws Exception {
        mvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(content().string("ok"));
        mvc.perform(get("/")).andExpect(status().isOk());
        mvc.perform(get("/index.html")).andExpect(status().isOk());
        mvc.perform(get("/styles.css")).andExpect(status().isOk());
        mvc.perform(get("/app.js")).andExpect(status().isOk());
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void missing_app_api_token_fails_startup() {
        new ApplicationContextRunner()
                .withUserConfiguration(PropertiesOnlyConfig.class)
                .run(
                        context -> {
                            assertThat(context).hasFailed();
                            assertThat(context.getStartupFailure())
                                    .hasRootCauseInstanceOf(IllegalStateException.class)
                                    .hasStackTraceContaining("APP_API_TOKEN 未配置");
                        });
    }

    @Configuration
    @EnableConfigurationProperties(ApiTokenProperties.class)
    static class PropertiesOnlyConfig {}

    @RestController
    static class TestApi {
        @GetMapping("/api/ping")
        String ping() {
            return "pong";
        }
    }
}
