package com.bizfty.anchon.dsh.context;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工作区指令加载（对应 DSH context/agent-instructions 的发现面，简化）。
 * <p>
 * 从会话 cwd 向上逐级查找 AGENTS.md / CLAUDE.md；按内容哈希去重
 * （同一内容只注入一次，与 DSH 一致）；有字节上限（fail loud 截断）。
 */
@Component
public class AgentInstructionsService {

    private static final List<String> FILE_NAMES = List.of("AGENTS.md", "CLAUDE.md");

    private final boolean enabled;
    private final long maxBytes;

    public AgentInstructionsService(@Value("${dsh.context.agent-instructions.enabled:true}") boolean enabled,
                                    @Value("${dsh.context.agent-instructions.max-bytes:65536}") long maxBytes) {
        this.enabled = enabled;
        this.maxBytes = maxBytes;
    }

    /** 一条指令。 */
    public record Instruction(String source, String content) {
    }

    /**
     * 发现并读取工作区指令（按内容去重，最近者优先）。
     *
     * @param cwd 会话工作目录（可空 → 当前目录）
     */
    public List<Instruction> find(String cwd) {
        if (!enabled) {
            return List.of();
        }
        Path start = cwd == null || cwd.isBlank() ? Paths.get("").toAbsolutePath() : Paths.get(cwd);
        if (!Files.isDirectory(start)) {
            return List.of();
        }
        Map<String, Instruction> byContent = new LinkedHashMap<>();
        List<Path> dirs = new ArrayList<>();
        Path current = start.toAbsolutePath().normalize();
        while (current != null) {
            dirs.add(current);
            current = current.getParent();
        }
        for (Path dir : dirs) {
            for (String fileName : FILE_NAMES) {
                Path file = dir.resolve(fileName);
                if (!Files.isRegularFile(file)) {
                    continue;
                }
                try {
                    long size = Files.size(file);
                    if (size > maxBytes) {
                        continue; // 超限跳过（DSH：超预算的指令文件不注入）
                    }
                    String content = Files.readString(file, StandardCharsets.UTF_8).trim();
                    if (content.isBlank()) {
                        continue;
                    }
                    String digest = sha256(content);
                    if (!byContent.containsKey(digest)) {
                        byContent.put(digest, new Instruction(file.toString(), content));
                    }
                } catch (IOException ignored) {
                    // 跳过不可读文件
                }
            }
        }
        return List.copyOf(byContent.values());
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
