package com.investmentresearch.app.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.investmentresearch.app.Application;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 档案接口照 {@code assistant/api.py:394-431}：404 / 400 / 分页的语义。 */
@SpringBootTest(
        classes = Application.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "app.api-token=test-token",
            "DEEPSEEK_API_KEY=",
            "management.server.port=0"
        })
class MemoryApiTest {
    private static final String DB = "target/memory-api-" + UUID.randomUUID() + ".db";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:file:" + DB + "?busy_timeout=5000");
        registry.add("APP_MEMORY_IMPORT", () -> legacyFixture().toString());
    }

    @Autowired TestRestTemplate rest;
    @Autowired ObjectMapper json;

    @Test
    void lists_imported_companies() throws Exception {
        JsonNode body = get("/api/research/companies", HttpStatus.OK);
        assertThat(body.get("companies")).hasSize(5);
        assertThat(body.get("companies").get(0).get("code").asText()).isEqualTo("601606");
    }

    @Test
    void detail_returns_sections_and_counts() throws Exception {
        JsonNode body = get("/api/research/companies/000858", HttpStatus.OK);
        assertThat(body.get("company").get("code").asText()).isEqualTo("000858");
        assertThat(body.get("counts").get("facts").asInt()).isEqualTo(2);
        assertThat(body.at("/facts/0/fact_id").asText()).isEqualTo("F002");
    }

    @Test
    void section_returns_total_and_honours_limit() throws Exception {
        JsonNode body = get("/api/research/companies/000858?section=facts&limit=1", HttpStatus.OK);
        assertThat(body.get("total").asInt()).isEqualTo(2);
        assertThat(body.get("records")).hasSize(1);
    }

    @Test
    void unknown_section_is_400_and_bad_code_or_missing_company_is_404() throws Exception {
        get("/api/research/companies/000858?section=unknown", HttpStatus.BAD_REQUEST);
        get("/api/research/companies/abc", HttpStatus.NOT_FOUND);
        get("/api/research/companies/600000", HttpStatus.NOT_FOUND);
    }

    private JsonNode get(String path, HttpStatus expected) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("test-token");
        ResponseEntity<String> response =
                rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(response.getStatusCode()).as(path).isEqualTo(expected);
        return json.readTree(response.getBody());
    }

    private static Path legacyFixture() {
        return repoRoot().resolve("core/src/test/resources/fixtures/research-memory.db");
    }

    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            Path pom = dir.resolve("pom.xml");
            try {
                if (Files.isRegularFile(pom)
                        && Files.readString(pom).contains("<module>app</module>")) {
                    return dir;
                }
            } catch (java.io.IOException ignored) {
                // keep walking up
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("找不到仓库根目录");
    }
}
