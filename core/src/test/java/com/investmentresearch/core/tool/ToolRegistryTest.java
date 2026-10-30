package com.investmentresearch.core.tool;

import static org.assertj.core.api.Assertions.assertThat;

import com.investmentresearch.core.domain.StockRef;
import com.investmentresearch.core.domain.ToolResult;
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
    void when_port_throws_registry_returns_failure() {
        CompanyProfilePort broken =
                code -> {
                    throw new IllegalStateException("上游挂了");
                };
        ToolRegistry registry =
                new ToolRegistry(List.of(new CompanyProfileTool(broken, resolver, SCHEMAS)));

        ToolResult result = registry.run("get_company_profile", Map.of("company", "605499"), null);

        assertThat(result.ok()).isFalse();
        assertThat(result.company()).isNull();
        assertThat(result.error()).isEqualTo("上游挂了");
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
