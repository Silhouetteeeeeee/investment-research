package com.investmentresearch.core.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.investmentresearch.core.domain.StockRef;
import com.investmentresearch.core.domain.ToolResult;
import com.investmentresearch.core.domain.ToolSchema;
import com.investmentresearch.core.port.CompanyDirectory;
import com.investmentresearch.core.port.CompanyProfilePort;
import com.investmentresearch.core.port.SegmentPort;
import com.investmentresearch.core.service.CompanyResolver;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ToolRegistryTest {

    private static final ToolSchemas SCHEMAS = ToolSchemas.loadDefault();
    private static final CompanyDirectory DIRECTORY =
            new CompanyDirectory() {
                @Override
                public Optional<StockRef> byCode(String code) {
                    return ALL.stream().filter(stock -> stock.code().equals(code)).findFirst();
                }

                @Override
                public Optional<StockRef> byName(String name) {
                    return ALL.stream().filter(stock -> stock.name().equals(name)).findFirst();
                }

                @Override
                public List<StockRef> all() {
                    return ALL;
                }

                private static final List<StockRef> ALL =
                        List.of(new StockRef("605499", "东鹏饮料"), new StockRef("000858", "五粮液"));
            };

    private final CompanyResolver resolver = new CompanyResolver(DIRECTORY);

    private static CompanyProfilePort profilePort(AtomicReference<String> seen) {
        return code -> {
            if (seen != null) {
                seen.set(code);
            }
            return ToolResult.ok(code, "2026-10-30", List.of(), Map.of("name", "东鹏饮料"));
        };
    }

    @Test
    void plus_appends_tools_in_order_and_shares_metrics() {
        ToolRegistry base = new ToolRegistry(List.of(schemaTool("a")));

        ToolRegistry all = base.plus(List.of(schemaTool("b")));

        assertThat(all.schemas())
                .extracting(ToolSchema::name)
                .containsExactly("a", "b");
        assertThat(all.metrics()).isSameAs(base.metrics());
    }

    private static ResearchTool schemaTool(String name) {
        ToolSchema schema = new ToolSchema(name, name, Map.of("type", "object"));
        return new ResearchTool() {
            @Override
            public ToolSchema schema() {
                return schema;
            }

            @Override
            public ToolResult execute(
                    Map<String, Object> arguments, String defaultCompany) {
                return ToolResult.ok(null, null, List.of(), Map.of());
            }
        };
    }

    @Test
    void registry_returns_schema_list_in_registration_order() {
        ToolRegistry registry =
                new ToolRegistry(
                        List.of(
                                new ResolveCompanyTool(DIRECTORY),
                                new CompanyProfileTool(profilePort(null), resolver, SCHEMAS),
                                new FinanceTool(code -> null, resolver, SCHEMAS)));

        assertThat(registry.schemas())
                .extracting("name")
                .containsExactly("resolve_company", "get_company_profile", "get_finance");
    }

    @Test
    void unknown_tool_returns_failure_not_exception() {
        ToolRegistry registry = new ToolRegistry(List.of());

        ToolResult result = registry.run("no_such_tool", Map.of(), null);

        assertThat(result.ok()).isFalse();
        assertThat(result.company()).isNull();
        assertThat(result.error()).isEqualTo("未知工具：no_such_tool");
    }

    @Test
    void records_execution_stats_including_failures() {
        ToolMetrics metrics = new ToolMetrics();
        ToolRegistry success =
                new ToolRegistry(
                        List.of(new CompanyProfileTool(profilePort(null), resolver, SCHEMAS)),
                        metrics);
        success.run("get_company_profile", Map.of("company", "605499"), null);

        CompanyProfilePort broken =
                code -> {
                    throw new IllegalStateException("上游挂了");
                };
        ToolRegistry failure =
                new ToolRegistry(
                        List.of(new CompanyProfileTool(broken, resolver, SCHEMAS)), metrics);
        failure.run("get_company_profile", Map.of("company", "605499"), null);

        ToolMetrics.ToolStat stat = metrics.snapshot().get(0);
        assertThat(stat.count()).isEqualTo(2);
        assertThat(stat.successes()).isEqualTo(1);
        assertThat(stat.successRate()).isEqualTo(0.5);
    }

    @Test
    void unknown_tool_with_valid_company_still_leaves_company_null() {
        ToolRegistry registry = new ToolRegistry(List.of());

        ToolResult result = registry.run("no_such_tool", Map.of("company", "605499"), null);

        // 与 Python 不同：这里不回填解析出的代码，Task 14 的约定
        assertThat(result.company()).isNull();
        assertThat(result.error()).isEqualTo("未知工具：no_such_tool");
    }

    @Test
    void missing_company_uses_default_company() {
        AtomicReference<String> seen = new AtomicReference<>();
        ToolRegistry registry =
                new ToolRegistry(
                        List.of(new CompanyProfileTool(profilePort(seen), resolver, SCHEMAS)));

        ToolResult result = registry.run("get_company_profile", Map.of(), "605499");

        assertThat(result.ok()).isTrue();
        assertThat(result.company()).isEqualTo("605499");
        assertThat(seen.get()).isEqualTo("605499");
    }

    @Test
    void company_argument_wins_over_default_company() {
        AtomicReference<String> seen = new AtomicReference<>();
        ToolRegistry registry =
                new ToolRegistry(
                        List.of(new CompanyProfileTool(profilePort(seen), resolver, SCHEMAS)));

        registry.run("get_company_profile", Map.of("company", "东鹏饮料"), "000858");

        assertThat(seen.get()).isEqualTo("605499");
    }

    @Test
    void missing_company_and_no_default_returns_failure() {
        ToolRegistry registry =
                new ToolRegistry(
                        List.of(new CompanyProfileTool(profilePort(null), resolver, SCHEMAS)));

        ToolResult result = registry.run("get_company_profile", Map.of(), null);

        assertThat(result.ok()).isFalse();
        assertThat(result.company()).isNull();
        assertThat(result.error()).isEqualTo("未指定公司：请先给出股票代码或简称");
    }

    @Test
    void unresolvable_company_returns_resolver_error() {
        ToolRegistry registry =
                new ToolRegistry(
                        List.of(new CompanyProfileTool(profilePort(null), resolver, SCHEMAS)));

        ToolResult result = registry.run("get_company_profile", Map.of("company", "不存在"), null);

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).isEqualTo("没有找到「不存在」");
    }

    @Test
    void when_port_throws_registry_returns_failure_with_resolved_code() {
        CompanyProfilePort broken =
                code -> {
                    throw new IllegalStateException("上游挂了");
                };
        ToolRegistry registry =
                new ToolRegistry(List.of(new CompanyProfileTool(broken, resolver, SCHEMAS)));

        ToolResult result = registry.run("get_company_profile", Map.of("company", "605499"), null);

        assertThat(result.ok()).isFalse();
        // Python run_tool 在解析成功后失败的路径同样带回 code
        assertThat(result.company()).isEqualTo("605499");
        assertThat(result.error()).isEqualTo("上游挂了");
    }

    @Test
    void port_exception_without_message_renders_empty_error_like_python_str() {
        CompanyProfilePort broken =
                code -> {
                    throw new IllegalStateException();
                };
        ToolRegistry registry =
                new ToolRegistry(List.of(new CompanyProfileTool(broken, resolver, SCHEMAS)));

        ToolResult result = registry.run("get_company_profile", Map.of("company", "605499"), null);

        assertThat(result.company()).isEqualTo("605499");
        assertThat(result.error()).isEmpty();
    }

    @Test
    void unknown_tool_with_non_string_company_returns_company_error_first() {
        ToolRegistry registry = new ToolRegistry(List.of());

        ToolResult result = registry.run("no_such_tool", Map.of("company", true), null);

        assertThat(result.ok()).isFalse();
        assertThat(result.company()).isNull();
        assertThat(result.error()).isEqualTo("company 参数必须是字符串（收到 bool）");
    }

    @Test
    void non_string_company_returns_python_type_error() {
        ToolRegistry registry =
                new ToolRegistry(
                        List.of(new CompanyProfileTool(profilePort(null), resolver, SCHEMAS)));

        assertCompanyTypeError(registry, true, "bool");
        assertCompanyTypeError(registry, 1.5d, "float");
        assertCompanyTypeError(registry, Map.of("a", 1), "dict");
        assertCompanyTypeError(registry, List.of("605499"), "list");
    }

    private static void assertCompanyTypeError(
            ToolRegistry registry, Object company, String pythonType) {
        ToolResult result =
                registry.run("get_company_profile", Map.of("company", company), null);

        assertThat(result.ok()).isFalse();
        assertThat(result.error())
                .isEqualTo("company 参数必须是字符串（收到 " + pythonType + "）");
    }

    @Test
    void null_arguments_map_falls_back_to_default_company() {
        AtomicReference<String> seen = new AtomicReference<>();
        ToolRegistry registry =
                new ToolRegistry(
                        List.of(new CompanyProfileTool(profilePort(seen), resolver, SCHEMAS)));

        ToolResult result = registry.run("get_company_profile", null, "605499");

        assertThat(result.ok()).isTrue();
        assertThat(seen.get()).isEqualTo("605499");
    }

    @Test
    void duplicate_tool_names_are_rejected() {
        assertThatThrownBy(
                        () ->
                                new ToolRegistry(
                                        List.of(
                                                new CompanyProfileTool(
                                                        profilePort(null), resolver, SCHEMAS),
                                                new CompanyProfileTool(
                                                        profilePort(null), resolver, SCHEMAS))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate tool: get_company_profile");
    }

    @Test
    void null_tool_element_is_rejected() {
        assertThatThrownBy(
                        () ->
                                new ToolRegistry(
                                        java.util.Arrays.asList(
                                                new CompanyProfileTool(
                                                        profilePort(null), resolver, SCHEMAS),
                                                null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tool must not be null");
    }

    @Test
    void segments_years_defaults_to_5_and_clamps_to_1_10() {
        assertThat(segmentsYears(null)).isEqualTo(5);
        assertThat(segmentsYears(0)).isEqualTo(5);
        assertThat(segmentsYears("")).isEqualTo(5);
        assertThat(segmentsYears(-3)).isEqualTo(1);
        assertThat(segmentsYears(42)).isEqualTo(10);
        assertThat(segmentsYears("7")).isEqualTo(7);
        assertThat(segmentsYears("abc")).isEqualTo(5);
        assertThat(segmentsYears(3_000_000_000L)).isEqualTo(10);
        assertThat(segmentsYears(-3_000_000_000L)).isEqualTo(1);
        assertThat(segmentsYears("3000000000")).isEqualTo(10);
    }

    private int segmentsYears(Object value) {
        AtomicInteger seen = new AtomicInteger(-1);
        SegmentPort port =
                (code, years) -> {
                    seen.set(years);
                    return ToolResult.ok(code, null, List.of(), Map.of());
                };
        ToolRegistry registry = new ToolRegistry(List.of(new SegmentsTool(port, resolver, SCHEMAS)));
        Map<String, Object> arguments =
                value == null ? Map.of("company", "605499") : Map.of("company", "605499", "years", value);
        registry.run("get_segments", arguments, null);
        return seen.get();
    }

    @Test
    void peers_limit_defaults_to_8_and_clamps_to_3_20() {
        assertThat(peersLimit(0)).isEqualTo(3);
        assertThat(peersLimit(null)).isEqualTo(8);
        assertThat(peersLimit(99)).isEqualTo(20);
        // 3e9 超过 int 范围：(int)(long) 会溢出成负数再夹成 3
        assertThat(peersLimit(3_000_000_000L)).isEqualTo(20);
    }

    private int peersLimit(Object value) {
        AtomicInteger seen = new AtomicInteger(-1);
        ToolRegistry registry =
                new ToolRegistry(
                        List.of(
                                new PeersTool(
                                        (code, limit) -> {
                                            seen.set(limit);
                                            return ToolResult.ok(code, null, List.of(), Map.of());
                                        },
                                        resolver,
                                        SCHEMAS)));
        Map<String, Object> arguments =
                value == null ? Map.of("company", "605499") : Map.of("company", "605499", "limit", value);
        registry.run("get_peers", arguments, null);
        return seen.get();
    }

    @Test
    void announcements_days_defaults_before_clamping() {
        assertThat(announcementDays(null)).isEqualTo(180);
        assertThat(announcementDays(0)).isEqualTo(180);
        assertThat(announcementDays("")).isEqualTo(180);
        assertThat(announcementDays(999)).isEqualTo(365);
        assertThat(announcementDays(1)).isEqualTo(1);
    }

    private int announcementDays(Object value) {
        AtomicInteger seen = new AtomicInteger(-1);
        ToolRegistry registry =
                new ToolRegistry(
                        List.of(
                                new AnnouncementsTool(
                                        (code, days) -> {
                                            seen.set(days);
                                            return ToolResult.ok(code, null, List.of(), Map.of());
                                        },
                                        resolver,
                                        SCHEMAS)));
        Map<String, Object> arguments =
                value == null ? Map.of("company", "605499") : Map.of("company", "605499", "days", value);
        registry.run("get_announcements", arguments, null);
        return seen.get();
    }
}
