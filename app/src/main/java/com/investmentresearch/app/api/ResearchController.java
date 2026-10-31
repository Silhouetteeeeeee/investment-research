package com.investmentresearch.app.api;

import com.investmentresearch.core.port.ResearchMemoryPort;
import com.investmentresearch.core.research.MemoryException;
import com.investmentresearch.core.research.MemoryRules;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Port of {@code assistant/api.py:394-431}: 档案列表与单公司详情。 */
@RestController
public class ResearchController {
    private final ResearchMemoryPort memory;

    public ResearchController(ResearchMemoryPort memory) {
        this.memory = memory;
    }

    @GetMapping("/api/research/companies")
    public Map<String, Object> companies(
            @RequestParam(value = "query", defaultValue = "") String query) {
        return Map.of("companies", memory.companies(query));
    }

    @GetMapping("/api/research/companies/{code}")
    public ResponseEntity<Map<String, Object>> company(
            @PathVariable("code") String code,
            @RequestParam(value = "limit", required = false) String limit,
            @RequestParam(value = "offset", required = false) String offset,
            @RequestParam(value = "status", defaultValue = "") String status,
            @RequestParam(value = "section", defaultValue = "") String section) {
        if (!code.matches("\\d{6}")) {
            return notFound();
        }
        int pageSize = Math.min(Math.max(parseInt(limit, 200), 1), 500);
        int pageOffset = Math.max(parseInt(offset, 0), 0);
        if (!section.isEmpty()) {
            if (!MemoryRules.SECTIONS.contains(section)) {
                return ResponseEntity.badRequest()
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("error", "unknown section"));
            }
            try {
                return ResponseEntity.ok(
                        memory.read(code, section, "", status, pageSize, pageOffset));
            } catch (MemoryException error) {
                return notFound();
            }
        }
        Map<String, Object> detail = memory.detail(code, status, limit == null ? null : pageSize);
        return detail == null ? notFound() : ResponseEntity.ok(detail);
    }

    private static int parseInt(String raw, int fallback) {
        if (raw == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException error) {
            return fallback;
        }
    }

    private static ResponseEntity<Map<String, Object>> notFound() {
        return ResponseEntity.status(404)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("error", "not found"));
    }
}
