package com.investmentresearch.app.api;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class AuditLogFilterTest {
    private final ObjectMapper json = new ObjectMapper();
    private final AuditLogFilter filter = new AuditLogFilter(json);
    private final Logger logger = (Logger) LoggerFactory.getLogger("audit");
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    @BeforeEach
    void attach() {
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(logs);
    }

    @Test
    void logs_one_json_line_and_never_the_token_or_query() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/conversations");
        request.setQueryString("query=secret-search");
        request.addHeader("Authorization", "Bearer super-secret-token");
        request.setRemoteAddr("10.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> ((HttpServletResponse) res).setStatus(200));

        assertThat(logs.list).hasSize(1);
        String line = logs.list.get(0).getFormattedMessage();
        JsonNode parsed = json.readTree(line);
        assertThat(parsed.get("method").asText()).isEqualTo("GET");
        assertThat(parsed.get("path").asText()).isEqualTo("/api/conversations");
        assertThat(parsed.get("status").asInt()).isEqualTo(200);
        assertThat(parsed.get("auth").asText()).isEqualTo("ok");
        assertThat(parsed.get("ip").asText()).isEqualTo("10.0.0.1");
        assertThat(line).doesNotContain("super-secret-token", "secret-search");
    }

    @Test
    void missing_token_is_logged_as_missing() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/conversations");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(
                request,
                response,
                (req, res) -> ((HttpServletResponse) res).setStatus(401));

        JsonNode parsed = json.readTree(logs.list.get(0).getFormattedMessage());
        assertThat(parsed.get("status").asInt()).isEqualTo(401);
        assertThat(parsed.get("auth").asText()).isEqualTo("missing");
    }

    @Test
    void wrong_token_is_logged_as_invalid() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/conversations");
        request.addHeader("Authorization", "Bearer wrong-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(
                request,
                response,
                (req, res) -> ((HttpServletResponse) res).setStatus(401));

        JsonNode parsed = json.readTree(logs.list.get(0).getFormattedMessage());
        assertThat(parsed.get("auth").asText()).isEqualTo("invalid");
    }

    @Test
    void non_api_paths_are_not_audited() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/health");

        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {});

        assertThat(logs.list).isEmpty();
    }
}
