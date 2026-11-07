package com.investmentresearch.core.trading;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Agent 自己维护的长期交易经验（一个 Markdown 文件）：收盘阶段整篇覆盖写，盘前读入提示词。
 * 覆盖前保留上一版 .bak，写失败不会破坏旧文档。
 */
public final class TradingExperience {
    public static final int MAX_CHARS = 20_000;
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS");

    private final Path dir;
    private final Clock clock;

    public TradingExperience(Path dir, Clock clock) {
        this.dir = dir;
        this.clock = clock;
    }

    public Path file() {
        return dir.resolve("experience.md");
    }

    public String read() {
        Path file = file();
        if (!Files.isRegularFile(file)) {
            return "";
        }
        try {
            return Files.readString(file, StandardCharsets.UTF_8).strip();
        } catch (IOException error) {
            throw new UncheckedIOException("读取交易经验失败：" + file, error);
        }
    }

    /** 最后修改时间；文件不存在时为 null。 */
    public String updatedAt() {
        Path file = file();
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            return STAMP.format(
                    LocalDateTime.ofInstant(
                            Files.getLastModifiedTime(file).toInstant(), clock.getZone()));
        } catch (IOException error) {
            throw new UncheckedIOException("读取交易经验时间失败：" + file, error);
        }
    }

    /** 整篇覆盖写；返回写入时间。校验失败抛 IllegalArgumentException，不动旧文件。 */
    public String save(String content) {
        String text = content == null ? "" : content.strip();
        if (text.isEmpty()) {
            throw new IllegalArgumentException("经验正文不能为空");
        }
        if (text.length() > MAX_CHARS) {
            throw new IllegalArgumentException("经验文档超过 " + MAX_CHARS + " 字符，请先删减");
        }
        Path file = file();
        Path backup = dir.resolve("experience.md.bak");
        Path temp = dir.resolve("experience.md.tmp");
        try {
            Files.createDirectories(dir);
            if (Files.isRegularFile(file)) {
                Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING);
            }
            Files.writeString(temp, text, StandardCharsets.UTF_8);
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException error) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException error) {
            throw new UncheckedIOException("写入交易经验失败：" + file, error);
        }
        return updatedAt();
    }
}
