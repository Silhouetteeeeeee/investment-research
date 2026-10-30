package com.investmentresearch.app.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.investmentresearch.app.Application;
import com.investmentresearch.core.agent.AssistantEngine;
import com.investmentresearch.core.port.CompanyDirectory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

// The @WebMvcTest cannot see the security chain. This boots the real context so a
// 401-after-async regression (DispatcherType.ASYNC) fails here — verified by removing
// the ASYNC permit: the stream is cut and the body read throws, not a clean status.
@SpringBootTest(
        classes = Application.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "app.api-token=test-token",
            "spring.datasource.url=jdbc:sqlite:file:target/ask-stream-test.db?busy_timeout=5000",
            "spring.web.resources.static-locations=classpath:/test-web/",
            "management.server.port=0"
        })
class AskStreamAuthTest {
    @Autowired TestRestTemplate rest;

    // The route needs the directory and the engine; stubbing them keeps this security test
    // offline and independent of any local DEEPSEEK_API_KEY.
    @MockitoBean CompanyDirectory directory;
    @MockitoBean AssistantEngine engine;

    @Test
    void authenticated_async_stream_completes_with_200_and_full_body() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("test-token");
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(java.util.List.of(MediaType.TEXT_EVENT_STREAM));

        ResponseEntity<String> response =
                rest.postForEntity(
                        "/api/ask",
                        new HttpEntity<>("{\"question\":\"你好\"}", headers),
                        String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType())
                .isNotNull()
                .satisfies(
                        type ->
                                assertThat(type.isCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                                        .isTrue());
        assertThat(response.getHeaders().getCacheControl()).contains("no-cache");
        assertThat(response.getHeaders().getFirst("X-Accel-Buffering")).isEqualTo("no");
        assertThat(response.getBody())
                .isEqualTo(
                        "data: {\"type\":\"warning\",\"text\":\"没认出你问的是哪家公司，"
                                + "请在问题里带上公司名或六位代码。\"}\n\n");
    }

    @Test
    void ask_without_token_returns_401_json() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> response =
                rest.postForEntity(
                        "/api/ask",
                        new HttpEntity<>("{\"question\":\"你好\"}", headers),
                        String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getHeaders().getContentType())
                .isNotNull()
                .satisfies(
                        type ->
                                assertThat(type.isCompatibleWith(MediaType.APPLICATION_JSON))
                                        .isTrue());
        assertThat(response.getBody()).isEqualTo("{\"error\":\"unauthorized\"}");
    }
}
