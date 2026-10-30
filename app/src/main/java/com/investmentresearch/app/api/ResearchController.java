package com.investmentresearch.app.api;

import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Phase 1 stubs for {@code assistant/api.py:394-431}; the research memory lands in Phase 3. */
@RestController
public class ResearchController {

    @GetMapping("/api/research/companies")
    public Map<String, Object> companies() {
        return Map.of("companies", List.of());
    }

    @GetMapping("/api/research/companies/{code}")
    public ResponseEntity<Map<String, String>> company(@PathVariable("code") String code) {
        return ResponseEntity.status(404)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("error", "not found"));
    }
}
