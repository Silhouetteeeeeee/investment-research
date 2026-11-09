package com.investmentresearch.core.trading;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TradingExperienceTest {
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-11-07T07:05:00Z"), ZoneId.of("Asia/Shanghai"));

    @TempDir Path dir;

    private TradingExperience experience() {
        return new TradingExperience(dir.resolve("trading"), CLOCK);
    }

    @Test
    void missing_file_reads_as_empty_without_creating_it() {
        TradingExperience experience = experience();

        assertThat(experience.read()).isEmpty();
        assertThat(experience.updatedAt()).isNull();
        assertThat(experience.file()).doesNotExist();
    }

    @Test
    void save_creates_the_directory_and_reads_back() {
        TradingExperience experience = experience();

        String updatedAt = experience.save("# 交易经验\n\n## 纪律\n- 止损后不要当天回补\n");

        assertThat(experience.read()).isEqualTo("# 交易经验\n\n## 纪律\n- 止损后不要当天回补");
        assertThat(experience.updatedAt()).isEqualTo(updatedAt).isNotBlank();
        assertThat(experience.file()).exists();
    }

    @Test
    void overwriting_keeps_the_previous_version_as_backup() throws IOException {
        TradingExperience experience = experience();
        experience.save("第一版");
        experience.save("第二版");

        assertThat(experience.read()).isEqualTo("第二版");
        assertThat(Files.readString(dir.resolve("trading").resolve("experience.md.bak")))
                .isEqualTo("第一版");
    }

    @Test
    void blank_and_oversized_content_are_rejected_without_touching_the_file() throws IOException {
        TradingExperience experience = experience();
        experience.save("原内容");

        assertThatThrownBy(() -> experience.save("   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能为空");
        assertThatThrownBy(() -> experience.save("经".repeat(TradingExperience.MAX_CHARS + 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("20000");

        assertThat(experience.read()).isEqualTo("原内容");
    }
}
