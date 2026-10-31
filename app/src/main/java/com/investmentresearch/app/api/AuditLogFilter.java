package com.investmentresearch.app.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 每个 /api 请求写一行 JSON 审计日志，方便按时间、路径、状态回查。
 *
 * <p>刻意不记录请求体、查询串和 Authorization 头：问题内容可能含用户隐私，令牌更不能落盘。
 * 过滤器排在 Spring Security 之前，因此 401 也会留下记录。
 *
 * <p>shortcut: SSE 只记录建流（{@code async=true}），流内成败看 {@code var/runs}；要覆盖流结束需加 AsyncListener。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public final class AuditLogFilter extends OncePerRequestFilter {
    private static final Logger AUDIT = LoggerFactory.getLogger("audit");

    private final ObjectMapper json;

    public AuditLogFilter(ObjectMapper json) {
        this.json = json;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        // 监控页每 3 秒轮询一次，记审计只会挤掉真正的操作记录。
        return !path.startsWith("/api/") || "/api/metrics".equals(path);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long start = System.nanoTime();
        String failure = null;
        try {
            chain.doFilter(request, response);
        } catch (ServletException | IOException | RuntimeException error) {
            // 只留异常类型：消息里可能带上游 URL 或数据片段。
            failure = error.getClass().getSimpleName();
            throw error;
        } finally {
            audit(request, response, start, failure);
        }
    }

    private void audit(
            HttpServletRequest request,
            HttpServletResponse response,
            long start,
            String failure) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("ts", Instant.now().toString());
        line.put("method", request.getMethod());
        line.put("path", request.getRequestURI());
        line.put("status", response.getStatus());
        line.put("duration_ms", (System.nanoTime() - start) / 1_000_000);
        line.put("ip", request.getRemoteAddr());
        line.put("auth", authOf(request, response.getStatus()));
        line.put("async", request.isAsyncStarted());
        if (failure != null) {
            line.put("failure", failure);
        }
        try {
            AUDIT.info(json.writeValueAsString(line));
        } catch (JsonProcessingException error) {
            // 审计写入失败不能影响请求本身。
            AUDIT.info("audit serialization failed: {}", error.getClass().getSimpleName());
        }
    }

    /** 认证失败时区分「没带令牌」与「令牌不对」，两者排查方向不同。 */
    private static String authOf(HttpServletRequest request, int status) {
        if (status != HttpServletResponse.SC_UNAUTHORIZED) {
            return "ok";
        }
        String header = request.getHeader("Authorization");
        return header == null || header.isBlank() ? "missing" : "invalid";
    }
}
