package com.investmentresearch.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** 阶段 2 的七个 skill 正文必须与 Python {@code assistant/skills.py} 逐字一致。 */
class SkillContentTest {
    private static final List<String> NAMES =
            List.of(
                    "moat",
                    "operating_history",
                    "financial_safety",
                    "growth_outlook",
                    "management",
                    "valuation",
                    "margin_of_safety",
                    "market_view",
                    "research_memory",
                    "news_events",
                    "sector_rotation",
                    "stock_screening",
                    "strategy_risk",
                    "review_eval");
    private static final Pattern TOOL =
            Pattern.compile("\\b(get_[a-z_]+|calc_[a-z_]+)\\b");

    @Test
    void every_skill_body_matches_the_python_instructions() {
        for (String name : NAMES) {
            assertThat(read(skillFile(name)).substring(frontmatterEnd(skillFile(name))))
                    .as(name)
                    .isEqualTo(snapshot(name));
        }
    }

    @Test
    void skill_frontmatter_names_match_directory() {
        for (String name : NAMES) {
            String text = read(skillFile(name));
            assertThat(text).as(name).startsWith("---\nname: " + name + "\n");
            assertThat(text.substring(4, frontmatterEnd(skillFile(name))))
                    .as(name)
                    .contains("\ndescription: ");
        }
    }

    @Test
    void skill_bodies_only_reference_tools_that_exist() {
        Set<String> names = toolNames();
        for (String name : NAMES) {
            Matcher matcher = TOOL.matcher(snapshot(name));
            Set<String> referenced = new LinkedHashSet<>();
            while (matcher.find()) {
                referenced.add(matcher.group(1));
            }
            // research_memory 只引用记忆工具，正文里没有 get_/calc_ 工具也是对的
            assertThat(referenced).as(name).isSubsetOf(names);
        }
    }

    /** {@code tool-schemas.json} 的键就是 Python {@code schemas()} 的全部工具名。 */
    private static Set<String> toolNames() {
        try (InputStream in = SkillContentTest.class.getResourceAsStream("/tool-schemas.json")) {
            if (in == null) {
                throw new IllegalStateException("缺少 /tool-schemas.json");
            }
            Set<String> keys = new LinkedHashSet<>();
            new ObjectMapper().readTree(in).fieldNames().forEachRemaining(keys::add);
            return keys;
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    /** 正文起点：frontmatter 结束的 {@code ---} 之后的空行。 */
    private static int frontmatterEnd(Path file) {
        String text = read(file);
        int close = text.indexOf("\n---\n", 3);
        int body = close + 5;
        return body < text.length() && text.charAt(body) == '\n' ? body + 1 : body;
    }

    private static Path skillFile(String name) {
        return skillsDir().resolve(name).resolve("SKILL.md");
    }

    private static String snapshot(String name) {
        String resource = "/skills-python/" + name + ".md";
        try (InputStream in = SkillContentTest.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("缺少 " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    /** Surefire 的工作目录是 {@code app/}，skills 在仓库根。 */
    private static Path skillsDir() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve("skills");
            if (Files.isDirectory(candidate) && Files.isDirectory(dir.resolve("core"))) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("找不到 skills 目录");
    }

}
