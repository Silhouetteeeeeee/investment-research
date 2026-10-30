package com.investmentresearch.app.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.investmentresearch.core.agent.AssistantEngine;
import com.investmentresearch.core.domain.Conversation;
import com.investmentresearch.core.domain.StoredMessage;
import com.investmentresearch.core.port.ConversationRepository;
import com.investmentresearch.core.port.RunStore;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@WebMvcTest(
        controllers = ConversationController.class,
        excludeAutoConfiguration = UserDetailsServiceAutoConfiguration.class)
@AutoConfigureMockMvc(addFilters = false)
class ConversationControllerTest {
    private static final String RUN_A = "20261030T010000-aaaaaaaa";
    private static final String RUN_B = "20261030T010000-bbbbbbbb";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @MockitoBean ConversationRepository repository;
    @MockitoBean RunStore runStore;
    @MockitoBean AssistantEngine engine;

    @Test
    void list_returns_frontend_field_names() throws Exception {
        when(repository.recent("", 50))
                .thenReturn(
                        List.of(
                                conversation(7, "东鹏饮料", "605499", 4),
                                conversation(8, null, "600519", 0)));

        mvc.perform(get("/api/conversations"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$[0].id").value(7))
                .andExpect(jsonPath("$[0].title").value("东鹏饮料 · 毛利率为什么下滑"))
                .andExpect(jsonPath("$[0].name").value("东鹏饮料"))
                .andExpect(jsonPath("$[0].code").value("605499"))
                .andExpect(jsonPath("$[0].count").value(4))
                .andExpect(jsonPath("$[0].updated").value("2026-10-30T01:02:00.000000"))
                .andExpect(jsonPath("$[1].name").value("600519"));

        verify(repository).recent("", 50);
    }

    @Test
    void detail_hides_raw_and_keeps_trace_count() throws Exception {
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("text", "毛利率为什么下滑？");

        Map<String, Object> assistant = new LinkedHashMap<>();
        assistant.put("role", "assistant");
        assistant.put("text", "原材料成本上升。");
        assistant.put("sources", List.of(Map.of("id", "finance:605499", "label", "财务")));
        assistant.put(
                "trace",
                List.of(
                        Map.of("round", 1, "name", "get_finance"),
                        Map.of("round", 2, "name", "get_segments")));
        assistant.put("issues", List.of("缺少现金流", "引用编号无法对应", "第三条不应出现"));
        assistant.put("run", Map.of("id", RUN_A, "file_bytes", 123));
        assistant.put("raw", List.of(Map.of("secret", "工具原文不应出现")));

        when(repository.find(1))
                .thenReturn(
                        Optional.of(
                                conversation(
                                        1,
                                        "东鹏饮料",
                                        "605499",
                                        2,
                                        Map.of("tokens", 1000, "percent", 1.8))));
        when(repository.messages(1))
                .thenReturn(
                        List.of(
                                new StoredMessage("user", user),
                                new StoredMessage("assistant", assistant)));

        mvc.perform(get("/api/conversations/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.title").value("东鹏饮料 · 毛利率为什么下滑"))
                .andExpect(jsonPath("$.code").value("605499"))
                .andExpect(jsonPath("$.name").value("东鹏饮料"))
                .andExpect(jsonPath("$.usage.tokens").value(1000))
                .andExpect(jsonPath("$.messages[0].role").value("user"))
                .andExpect(jsonPath("$.messages[0].text").value("毛利率为什么下滑？"))
                .andExpect(jsonPath("$.messages[0].sources").doesNotExist())
                .andExpect(jsonPath("$.messages[0].run").doesNotExist())
                .andExpect(jsonPath("$.messages[1].role").value("assistant"))
                .andExpect(jsonPath("$.messages[1].trace.length()").value(2))
                .andExpect(jsonPath("$.messages[1].trace_count").value(2))
                .andExpect(jsonPath("$.messages[1].sources[0].id").value("finance:605499"))
                .andExpect(jsonPath("$.messages[1].issues.length()").value(2))
                .andExpect(jsonPath("$.messages[1].issues[0]").value("缺少现金流"))
                .andExpect(jsonPath("$.messages[1].issues[1]").value("引用编号无法对应"))
                .andExpect(jsonPath("$.messages[1].run.id").value(RUN_A))
                .andExpect(jsonPath("$.messages[1].raw").doesNotExist());
    }

    @Test
    void detail_unknown_conversation_returns_404() throws Exception {
        when(repository.find(404)).thenReturn(Optional.empty());

        mvc.perform(get("/api/conversations/404"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not found"));
    }

    @Test
    void evidence_returns_last_matching_tool_payload() throws Exception {
        // 第一条是 Python 历史形态，第二条是 Java 落库的工具结果本身；后者更新，应胜出
        Map<String, Object> pythonTool = new LinkedHashMap<>();
        pythonTool.put("role", "tool");
        pythonTool.put(
                "content",
                "{\"ok\":true,\"sources\":[{\"id\":\"finance:605499\"}],\"data\":{\"value\":\"old\"}}");
        Map<String, Object> javaTool = new LinkedHashMap<>();
        javaTool.put("ok", true);
        javaTool.put("sources", List.of(Map.of("id", "finance:605499")));
        javaTool.put("data", Map.of("value", "new"));
        Map<String, Object> assistant = new LinkedHashMap<>();
        assistant.put("role", "assistant");
        assistant.put("raw", List.of(pythonTool, javaTool));

        when(repository.find(1)).thenReturn(Optional.of(conversation(1, "东鹏饮料", "605499", 1)));
        when(repository.messages(1))
                .thenReturn(List.of(new StoredMessage("assistant", assistant)));

        mvc.perform(get("/api/conversations/1/evidence").param("source_id", "finance:605499"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source_id").value("finance:605499"))
                .andExpect(jsonPath("$.evidence.data.value").value("new"));
    }

    @Test
    void evidence_with_unknown_source_returns_null_evidence_not_404() throws Exception {
        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("ok", true);
        tool.put("sources", List.of(Map.of("id", "finance:605499")));
        tool.put("data", Map.of("value", "x"));
        Map<String, Object> assistant = new LinkedHashMap<>();
        assistant.put("role", "assistant");
        assistant.put("raw", List.of(tool));

        when(repository.find(1)).thenReturn(Optional.of(conversation(1, "东鹏饮料", "605499", 1)));
        when(repository.messages(1))
                .thenReturn(List.of(new StoredMessage("assistant", assistant)));

        MvcResult result =
                mvc.perform(get("/api/conversations/1/evidence").param("source_id", "missing:1"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.source_id").value("missing:1"))
                        .andReturn();

        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        assertThat(body.has("evidence")).isTrue();
        assertThat(body.get("evidence").isNull()).isTrue();
    }

    @Test
    void evidence_without_source_id_returns_400() throws Exception {
        mvc.perform(get("/api/conversations/1/evidence"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("missing source_id"));
    }

    @Test
    void run_detail_rejects_run_id_not_referenced_by_conversation() throws Exception {
        when(repository.find(1)).thenReturn(Optional.of(conversation(1, "东鹏饮料", "605499", 1)));
        when(repository.messages(1))
                .thenReturn(
                        List.of(
                                new StoredMessage(
                                        "assistant", Map.of("run", Map.of("id", RUN_A)))));

        mvc.perform(get("/api/conversations/1/runs/{runId}", RUN_B))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not found"));

        verifyNoInteractions(runStore);
    }

    @Test
    void run_detail_rejects_path_traversal_id() throws Exception {
        when(repository.find(1)).thenReturn(Optional.of(conversation(1, "东鹏饮料", "605499", 1)));
        // 让这条消息真的"引用"穿越串：否则 referenced() 先拒，validRunId 一次都没跑到
        when(repository.messages(1))
                .thenReturn(
                        List.of(
                                new StoredMessage(
                                        "assistant", Map.of("run", Map.of("id", "..\\secret")))));

        mvc.perform(get("/api/conversations/1/runs/{runId}", "..\\secret"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not found"));

        verifyNoInteractions(runStore);
    }

    @Test
    void run_detail_returns_run_payload_with_no_store_cache_header() throws Exception {
        when(repository.find(1)).thenReturn(Optional.of(conversation(1, "东鹏饮料", "605499", 2)));
        when(repository.messages(1))
                .thenReturn(
                        List.of(
                                new StoredMessage(
                                        "assistant", Map.of("run", Map.of("id", RUN_A)))));
        when(runStore.read(RUN_A)).thenReturn(Optional.of(Map.of("run_id", RUN_A, "stages", List.of())));

        mvc.perform(get("/api/conversations/1/runs/{runId}", RUN_A))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.run_id").value(RUN_A));
    }

    @Test
    void delete_removes_conversation_messages_runs_and_session() throws Exception {
        when(repository.messages(9))
                .thenReturn(
                        List.of(
                                new StoredMessage(
                                        "assistant", Map.of("run", Map.of("id", RUN_A))),
                                new StoredMessage(
                                        "assistant", Map.of("run", Map.of("id", RUN_B))),
                                new StoredMessage("user", Map.of("text", "你好"))));

        mvc.perform(post("/api/conversations/9/delete"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));

        InOrder order = inOrder(repository, runStore, engine);
        order.verify(repository).messages(9);
        order.verify(repository).delete(9);
        order.verify(runStore).deleteAll(List.of(RUN_A, RUN_B));
        order.verify(engine).deleteSession(9);
    }

    private static Conversation conversation(
            long id, String name, String code, int messageCount) {
        return conversation(id, name, code, messageCount, Map.of());
    }

    private static Conversation conversation(
            long id, String name, String code, int messageCount, Map<String, Object> usage) {
        return new Conversation(
                id,
                "东鹏饮料 · 毛利率为什么下滑",
                code,
                name,
                List.of(code),
                "2026-10-30T01:00:00.000000",
                "2026-10-30T01:02:00.000000",
                messageCount,
                usage);
    }
}
