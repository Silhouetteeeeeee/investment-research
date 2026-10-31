package com.investmentresearch.app.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VersionStampTest {
    @TempDir Path temp;

    @Test
    void prompt_hash_is_short_and_ignores_line_endings() throws Exception {
        Path skills = skills("alpha", "content");
        Path questions = write(temp.resolve("questions.json"), "{}");

        VersionStamp crlf =
                VersionStamp.of(
                        "a\r\nb".getBytes(StandardCharsets.UTF_8),
                        "{}".getBytes(StandardCharsets.UTF_8),
                        skills,
                        questions,
                        "abc");
        VersionStamp lf =
                VersionStamp.of(
                        "a\nb".getBytes(StandardCharsets.UTF_8),
                        "{}".getBytes(StandardCharsets.UTF_8),
                        skills,
                        questions,
                        "abc");

        assertThat(crlf.promptVersion()).hasSize(12).isEqualTo(lf.promptVersion());
    }

    @Test
    void editing_one_skill_changes_only_its_hash_and_the_aggregate() throws Exception {
        Path skills = skills("alpha", "first", "beta", "second");
        Path questions = write(temp.resolve("questions.json"), "{}");
        byte[] prompt = "prompt".getBytes(StandardCharsets.UTF_8);
        byte[] tools = "{}".getBytes(StandardCharsets.UTF_8);

        VersionStamp before = VersionStamp.of(prompt, tools, skills, questions, "abc");
        Files.writeString(
                skills.resolve("beta").resolve("SKILL.md"),
                "second, edited",
                StandardCharsets.UTF_8);
        VersionStamp after = VersionStamp.of(prompt, tools, skills, questions, "abc");

        assertThat(after.skills()).containsOnlyKeys("alpha", "beta");
        assertThat(after.skills().get("alpha")).isEqualTo(before.skills().get("alpha"));
        assertThat(after.skills().get("beta")).isNotEqualTo(before.skills().get("beta"));
        assertThat(after.skillsVersion()).isNotEqualTo(before.skillsVersion());
        assertThat(after.promptVersion()).isEqualTo(before.promptVersion());
    }

    @Test
    void dataset_and_tool_changes_change_their_own_versions() throws Exception {
        Path skills = skills("alpha", "content");
        Path questions = write(temp.resolve("questions.json"), "{\"questions\":[]}");
        byte[] prompt = "prompt".getBytes(StandardCharsets.UTF_8);

        VersionStamp before =
                VersionStamp.of(
                        prompt, "{\"a\":1}".getBytes(StandardCharsets.UTF_8), skills, questions, "abc");
        VersionStamp toolsChanged =
                VersionStamp.of(
                        prompt, "{\"a\":2}".getBytes(StandardCharsets.UTF_8), skills, questions, "abc");
        // 另写一个文件，避免覆盖 before / toolsChanged 已经读过的那份
        VersionStamp datasetChanged =
                VersionStamp.of(
                        prompt,
                        "{\"a\":1}".getBytes(StandardCharsets.UTF_8),
                        skills,
                        write(temp.resolve("questions-2.json"), "{\"questions\":[1]}"),
                        "abc");

        assertThat(datasetChanged.datasetVersion()).isNotEqualTo(before.datasetVersion());
        assertThat(datasetChanged.toolsVersion()).isEqualTo(before.toolsVersion());
        assertThat(toolsChanged.toolsVersion()).isNotEqualTo(before.toolsVersion());
        assertThat(toolsChanged.datasetVersion()).isEqualTo(before.datasetVersion());
    }

    @Test
    void git_version_is_unknown_outside_a_repository() throws Exception {
        assertThat(VersionStamp.gitVersion(temp)).isEqualTo("unknown");
    }

    @Test
    void capture_reads_the_real_prompt_and_all_skills() throws Exception {
        VersionStamp stamp =
                VersionStamp.capture(Path.of(".."), Path.of("../eval/questions.json"));

        assertThat(stamp.promptVersion()).hasSize(12);
        assertThat(stamp.toolsVersion()).hasSize(12);
        assertThat(stamp.datasetVersion()).hasSize(12);
        assertThat(stamp.skills()).hasSize(15).containsKeys("business_model", "valuation");
        assertThat(stamp.asMap())
                .containsKeys(
                        "agent_version",
                        "prompt_version",
                        "skills_version",
                        "tools_version",
                        "dataset_version");
    }

    private Path skills(String... namesAndContents) throws Exception {
        Path root = Files.createDirectories(temp.resolve("skills-" + namesAndContents[0]));
        for (int i = 0; i < namesAndContents.length; i += 2) {
            Path dir = Files.createDirectories(root.resolve(namesAndContents[i]));
            Files.writeString(
                    dir.resolve("SKILL.md"), namesAndContents[i + 1], StandardCharsets.UTF_8);
        }
        return root;
    }

    private static Path write(Path file, String content) throws Exception {
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }
}
