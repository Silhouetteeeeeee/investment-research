package com.investmentresearch.core.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileRunStoreTest {
    private static final String RUN_ID = "20261030T145803-b43207aa";

    @TempDir Path tempDir;

    @Test
    void write_is_atomic_and_readable() throws IOException {
        FileRunStore store = new FileRunStore(tempDir);
        Map<String, Object> payload =
                Map.of("run_id", RUN_ID, "version", 1, "question", "研究一下");

        RunSummary summary = store.write(payload);

        Path file = tempDir.resolve(RUN_ID + ".json");
        assertThat(summary.id()).isEqualTo(RUN_ID);
        assertThat(summary.fileBytes()).isEqualTo(Files.size(file));
        assertThat(store.read(RUN_ID)).contains(payload);
        try (Stream<Path> files = Files.list(tempDir)) {
            assertThat(files.map(path -> path.getFileName().toString()))
                    .containsExactly(RUN_ID + ".json");
        }
    }

    @Test
    void invalid_run_id_is_rejected_without_writing() throws IOException {
        FileRunStore store = new FileRunStore(tempDir);

        assertThatThrownBy(() -> store.write(Map.of("run_id", "../x", "version", 1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.write(Map.of("run_id", "a/b", "version", 1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.write(Map.of("run_id", "short", "version", 1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.write(Map.of("version", 1)))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(store.read("../x")).isEmpty();
        try (Stream<Path> files = Files.list(tempDir)) {
            assertThat(files).isEmpty();
        }
    }

    @Test
    void keeps_only_latest_200_by_mtime() throws IOException {
        Path directory = tempDir.resolve("logs");
        Files.createDirectories(directory);
        for (int i = 1; i <= 201; i++) {
            String id = "run" + String.format("%06d", i);
            Path file = directory.resolve(id + ".json");
            Files.writeString(file, "{}");
            Files.setLastModifiedTime(file, FileTime.fromMillis(i * 1000L));
        }
        FileRunStore store = new FileRunStore(directory);

        store.write(Map.of("run_id", RUN_ID, "version", 1));

        try (Stream<Path> files = Files.list(directory)) {
            List<String> names = files.map(path -> path.getFileName().toString()).toList();
            assertThat(names).hasSize(200);
            assertThat(names).contains(RUN_ID + ".json", "run000201.json");
            assertThat(names).doesNotContain("run000001.json", "run000002.json");
        }
    }

    @Test
    void deleteAll_ignores_invalid_ids() throws IOException {
        FileRunStore store = new FileRunStore(tempDir);
        String kept = "20261030T111712-076e248e";
        store.write(Map.of("run_id", RUN_ID, "version", 1));
        store.write(Map.of("run_id", kept, "version", 1));

        store.deleteAll(List.of(RUN_ID, "../x", "a/b", "short"));

        assertThat(store.read(RUN_ID)).isEmpty();
        assertThat(store.read(kept)).isPresent();
    }
}
