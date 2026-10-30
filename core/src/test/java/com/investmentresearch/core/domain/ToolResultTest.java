package com.investmentresearch.core.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class ToolResultTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void message_omits_null_fields_and_always_writes_sources() {
        ToolResult result = new ToolResult(true, null, null, List.of(), null, null, false);
        assertEquals("{\"ok\": true, \"sources\": []}", result.toMessageJson());
    }

    @Test
    void cached_result_carries_note() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", "东鹏饮料");
        data.put("employees", 1234);
        Source source =
                new Source(
                        "profile:605499",
                        "公司概况",
                        "东鹏饮料 · 饮料",
                        "2026-10-30",
                        "2026-10-28 10:00:00");
        ToolResult result =
                ToolResult.ok("605499", "2026-10-30", List.of(source), data).asCached();

        assertTrue(result.cached());
        assertEquals(
                "{\"ok\": true, \"sources\": [{\"id\": \"profile:605499\", \"label\": \"公司概况\","
                        + " \"detail\": \"东鹏饮料 · 饮料\", \"as_of\": \"2026-10-30\","
                        + " \"fetched_at\": \"2026-10-28 10:00:00\", \"url\": \"\"}],"
                        + " \"cached\": true, \"note\": \"本次会话已取过相同参数的数据，未重新请求；"
                        + "此前那份已从上下文移除\", \"company\": \"605499\","
                        + " \"as_of\": \"2026-10-30\", \"data\": {\"name\": \"东鹏饮料\","
                        + " \"employees\": 1234}}",
                result.toMessageJson());
    }

    @Test
    void failure_has_ok_false_and_error() {
        ToolResult result = ToolResult.failure("000858", "没有财报数据");

        assertFalse(result.ok());
        assertEquals(
                "{\"ok\": false, \"sources\": [], \"company\": \"000858\","
                        + " \"error\": \"没有财报数据\"}",
                result.toMessageJson());
    }

    @Test
    void floats_match_python_repr() {
        double[] values = {1e-5, 1e16, 1.0, 1e20, 0.0001, -0.0, 60.0, 1.234e7, 0.1, 1234567890123456.0};
        Map<String, Object> data = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i++) {
            data.put("v" + i, values[i]);
        }
        ToolResult result = new ToolResult(true, null, null, List.of(), data, null, false);

        assertEquals(
                "{\"ok\": true, \"sources\": [], \"data\": {\"v0\": 1e-05, \"v1\": 1e+16,"
                        + " \"v2\": 1.0, \"v3\": 1e+20, \"v4\": 0.0001, \"v5\": -0.0,"
                        + " \"v6\": 60.0, \"v7\": 12340000.0, \"v8\": 0.1,"
                        + " \"v9\": 1234567890123456.0}}",
                result.toMessageJson());
    }

    @Test
    void strings_escape_like_python() {
        Source source =
                new Source(
                        "a\"b\\c",
                        "换行\n制表\t",
                        "\b\f\u0001\u001f",
                        "中文/斜杠",
                        "",
                        "https://x/y");
        ToolResult result = new ToolResult(true, null, null, List.of(source), null, null, false);

        assertEquals(
                "{\"ok\": true, \"sources\": [{\"id\": \"a\\\"b\\\\c\", \"label\":"
                        + " \"换行\\n制表\\t\", \"detail\": \"\\b\\f\\u0001\\u001f\", \"as_of\":"
                        + " \"中文/斜杠\", \"fetched_at\": \"\", \"url\": \"https://x/y\"}]}",
                result.toMessageJson());
    }

    @Test
    void message_matches_python_for_every_golden_case() throws IOException {
        Path directory = resource("/golden/tools");
        Map<String, String> messages =
                MAPPER.readValue(
                        Files.readString(resource("/golden/tool-messages.json")),
                        new TypeReference<Map<String, String>>() {});

        List<Path> files;
        try (Stream<Path> stream = Files.list(directory)) {
            files = stream.filter(file -> file.toString().endsWith(".json")).toList();
        }
        assertEquals(34, files.size());
        assertEquals(34, messages.size());

        for (Path file : files) {
            String name = file.getFileName().toString();
            Map<String, Object> golden =
                    MAPPER.readValue(Files.readString(file), new TypeReference<>() {});
            assertNotNull(messages.get(name), name);
            assertNotNull(golden.get("generated_on"), name);
            assertEquals(messages.get(name), toToolResult(golden).toMessageJson(), name);
        }
    }

    private static Path resource(String name) {
        var url = ToolResultTest.class.getResource(name);
        assertNotNull(url, name);
        try {
            return Path.of(url.toURI());
        } catch (Exception error) {
            throw new IllegalStateException("cannot resolve " + name, error);
        }
    }

    @SuppressWarnings("unchecked")
    private static ToolResult toToolResult(Map<String, Object> golden) {
        Map<String, Object> result = (Map<String, Object>) golden.get("result");
        List<Source> sources = new ArrayList<>();
        for (Map<String, Object> item : (List<Map<String, Object>>) result.get("sources")) {
            sources.add(
                    new Source(
                            (String) item.get("id"),
                            (String) item.get("label"),
                            (String) item.get("detail"),
                            (String) item.get("as_of"),
                            (String) item.get("fetched_at"),
                            (String) item.get("url")));
        }
        return new ToolResult(
                (Boolean) result.get("ok"),
                (String) result.get("company"),
                (String) result.get("as_of"),
                sources,
                (Map<String, Object>) result.get("data"),
                (String) result.get("error"),
                (Boolean) result.get("cached"));
    }
}
