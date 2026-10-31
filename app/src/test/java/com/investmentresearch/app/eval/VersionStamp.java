package com.investmentresearch.app.eval;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

/**
 * 评测结果里的版本身份。全部用内容哈希：改一个字节就变，不靠手写版本号。
 *
 * <p>prompt 和 tools 从 classpath 读，即 AgentEngine 运行时真正加载的那份；skills 从运行时目录读。
 * agent_version 取 git 短 SHA，工作区脏时加 {@code -dirty}——脏树下的版本号不可信，必须标出来。
 */
public record VersionStamp(
        String agentVersion,
        String promptVersion,
        String skillsVersion,
        Map<String, String> skills,
        String toolsVersion,
        String datasetVersion) {
    static final int SHORT_HASH = 12;

    /** 默认入口：相对仓库根取 skills 与题库。 */
    public static VersionStamp capture(Path repoRoot, Path questionsFile) throws IOException {
        return of(
                resource("/prompts/system.md"),
                resource("/tool-schemas.json"),
                repoRoot.resolve("skills"),
                questionsFile,
                gitVersion(repoRoot));
    }

    static VersionStamp of(
            byte[] prompt, byte[] tools, Path skillsDir, Path questionsFile, String agentVersion)
            throws IOException {
        Map<String, String> skillHashes = new TreeMap<>();
        for (Path dir :
                Files.list(skillsDir)
                        .filter(Files::isDirectory)
                        .sorted()
                        .toList()) {
            Path skillFile = dir.resolve("SKILL.md");
            if (!Files.isRegularFile(skillFile)) {
                continue;
            }
            skillHashes.put(
                    dir.getFileName().toString(),
                    hash(normalize(Files.readAllBytes(skillFile))));
        }
        StringBuilder aggregate = new StringBuilder();
        skillHashes.forEach(
                (name, hash) -> aggregate.append(name).append(':').append(hash).append('\n'));

        return new VersionStamp(
                agentVersion,
                hash(normalize(prompt)),
                hash(aggregate.toString().getBytes(StandardCharsets.UTF_8)),
                Collections.unmodifiableMap(skillHashes),
                hash(normalize(tools)),
                hash(normalize(Files.readAllBytes(questionsFile))));
    }

    /** 评测报告头部直接展开这个 map。 */
    public Map<String, Object> asMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("agent_version", agentVersion);
        map.put("prompt_version", promptVersion);
        map.put("skills_version", skillsVersion);
        map.put("skills", skills);
        map.put("tools_version", toolsVersion);
        map.put("dataset_version", datasetVersion);
        return map;
    }

    private static byte[] resource(String name) throws IOException {
        try (InputStream stream = VersionStamp.class.getResourceAsStream(name)) {
            if (stream == null) {
                throw new IOException("classpath 上找不到 " + name);
            }
            return stream.readAllBytes();
        }
    }

    /** 统一换行，避免 Windows 检出把同一份内容算成两个版本。 */
    private static byte[] normalize(byte[] bytes) {
        String text = new String(bytes, StandardCharsets.UTF_8);
        return text.replace("\r\n", "\n").replace('\r', '\n').getBytes(StandardCharsets.UTF_8);
    }

    private static String hash(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder(SHORT_HASH);
            for (int i = 0; i < SHORT_HASH / 2; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("JVM 缺少 SHA-256", error);
        }
    }

    static String gitVersion(Path repoRoot) {
        try {
            String head = git(repoRoot, "rev-parse", "--short", "HEAD");
            if (head.isEmpty()) {
                return "unknown";
            }
            String status = git(repoRoot, "status", "--porcelain");
            return status.isEmpty() ? head : head + "-dirty";
        } catch (Exception error) {
            return "unknown";
        }
    }

    private static String git(Path repoRoot, String... args) throws Exception {
        String[] command = new String[args.length + 3];
        command[0] = "git";
        command[1] = "-C";
        command[2] = repoRoot.toString();
        System.arraycopy(args, 0, command, 3, args.length);
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output =
                new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8)
                        .strip();
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("git 超时");
        }
        if (process.exitValue() != 0) {
            throw new IOException("git 退出码 " + process.exitValue());
        }
        return output;
    }
}
