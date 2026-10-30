package com.investmentresearch.core.run;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.investmentresearch.core.port.RunStore;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Port of Python's {@code assistant.runlog}: one JSON file per run, atomic and pruned. */
public final class FileRunStore implements RunStore {
    private static final int DEFAULT_KEEP = 200;
    private static final Pattern RUN_ID = Pattern.compile("[0-9A-Za-z_-]{8,64}");

    private final Path directory;
    private final int keep;
    private final ObjectMapper mapper = new ObjectMapper();

    public FileRunStore(Path directory) {
        this(directory, DEFAULT_KEEP);
    }

    public FileRunStore(Path directory, int keep) {
        this.directory = directory;
        this.keep = Math.max(1, keep);
    }

    @Override
    public RunSummary write(Map<String, Object> payload) {
        Object rawId = payload.get("run_id");
        String runId = rawId == null ? "" : rawId.toString();
        if (!validId(runId)) {
            throw new IllegalArgumentException("非法的运行 id：" + runId);
        }
        byte[] data;
        try {
            Files.createDirectories(directory);
            data = mapper.writeValueAsBytes(payload);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
        Path path = directory.resolve(runId + ".json");
        Path temp = directory.resolve("." + runId + "." + ProcessHandle.current().pid() + ".tmp");
        try {
            Files.write(temp, data);
            Files.move(
                    temp,
                    path,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        } finally {
            deleteQuietly(temp);
        }
        prune();
        return new RunSummary(runId, data.length);
    }

    @Override
    public Optional<Map<String, Object>> read(String runId) {
        if (!validId(runId)) {
            return Optional.empty();
        }
        try {
            byte[] data = Files.readAllBytes(directory.resolve(runId + ".json"));
            return Optional.of(mapper.readValue(data, new TypeReference<Map<String, Object>>() {}));
        } catch (IOException error) {
            return Optional.empty();
        }
    }

    @Override
    public void deleteAll(List<String> runIds) {
        for (String runId : runIds == null ? List.<String>of() : runIds) {
            if (validId(runId)) {
                deleteQuietly(directory.resolve(runId + ".json"));
            }
        }
    }

    /** 运行 id 只允许字母数字和 {@code _-}；不接受路径分隔符与 {@code ..}。 */
    static boolean validId(String runId) {
        return runId != null && RUN_ID.matcher(runId).matches() && !runId.contains("..");
    }

    /** 按修改时间保留最新 {@link #keep} 份；装饰性的运行原文不能无限增长。 */
    private void prune() {
        try (Stream<Path> files = Files.list(directory)) {
            List<Path> logs =
                    files.filter(path -> path.getFileName().toString().endsWith(".json"))
                            .sorted(Comparator.comparingLong(FileRunStore::modified).reversed())
                            .toList();
            for (int i = keep; i < logs.size(); i++) {
                deleteQuietly(logs.get(i));
            }
        } catch (IOException ignored) {
            // 清理失败不影响本次写入，下次写入再试。
        }
    }

    private static long modified(Path path) {
        try {
            FileTime time = Files.getLastModifiedTime(path);
            return time.toMillis();
        } catch (IOException error) {
            return 0L;
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 临时文件或过期原文删不掉不是致命问题。
        }
    }
}
