package com.investmentresearch.core.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.investmentresearch.core.domain.ToolSchema;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ToolSchemasTest {

    @Test
    void loads_all_38_schemas_from_export() throws IOException {
        Map<String, Map<String, Object>> exported;
        try (InputStream in = ToolSchemas.class.getResourceAsStream("/tool-schemas.json")) {
            assertNotNull(in);
            exported =
                    new ObjectMapper()
                            .readValue(
                                    in,
                                    new TypeReference<Map<String, Map<String, Object>>>() {});
        }
        // 33 条来自 Python schemas()，第 34 条 web_search 与第 35–38 条交易工具
        // （get_trading_state / save_trade_plan / save_trading_journal /
        // save_trading_experience）是 Java 新增
        assertEquals(38, exported.size());

        ToolSchemas schemas = ToolSchemas.loadDefault();
        for (String name : exported.keySet()) {
            ToolSchema schema = schemas.get(name);
            assertEquals(name, schema.name());
            assertNotNull(schema.description(), name);
            assertNotNull(schema.parameters(), name);
        }
    }

    @Test
    void missing_name_throws_illegal_state() {
        ToolSchemas schemas = ToolSchemas.loadDefault();
        assertThrows(IllegalStateException.class, () -> schemas.get("not_a_tool"));
    }
}
