package com.bizfty.anchon.dsh.fs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 文件路径策略 — 工作区包含性检查（对应 DSH fs-sandbox 的"策略栅栏"定位）。
 * <p>
 * 明确非目标：不是内核安全边界（TOCTOU 只收窄不消除）。写操作限制在
 * 会话工作区内；读操作放行任意路径。
 */
public final class FsPathPolicy {

    private FsPathPolicy() {
    }

    /** 规范化绝对路径（解析符号链接）。 */
    public static Path normalize(String rawPath, String cwd) {
        Path base = cwd == null || cwd.isBlank() ? Paths.get("").toAbsolutePath() : Paths.get(cwd);
        Path resolved = base.resolve(rawPath == null ? "" : rawPath).normalize();
        try {
            Path real = resolved.toRealPath();
            return real;
        } catch (IOException e) {
            return resolved.toAbsolutePath().normalize();
        }
    }

    /** 检查 target 是否位于 workspaceRoot 之内（包含）。 */
    public static boolean isWithin(Path target, String workspaceRoot) {
        if (workspaceRoot == null || workspaceRoot.isBlank()) {
            return true;
        }
        Path root = normalize(workspaceRoot, null);
        return target.startsWith(root);
    }

    /** 写路径必须位于工作区；返回拒绝理由或 null。 */
    public static String checkWritable(Path target, String workspaceRoot) {
        if (!isWithin(target, workspaceRoot)) {
            return "路径不在工作区内: " + target + " (工作区: " + workspaceRoot + ")";
        }
        return null;
    }

    /** 按沙箱模式检查写路径（READ_ONLY 一律拒绝；DANGER 放行；默认工作区限制）。 */
    public static String checkWritable(Path target, String workspaceRoot,
                                       com.bizfty.anchon.dsh.tool.SandboxMode mode) {
        if (mode == com.bizfty.anchon.dsh.tool.SandboxMode.READ_ONLY) {
            return "只读模式（read-only），禁止写文件: " + target;
        }
        if (mode == com.bizfty.anchon.dsh.tool.SandboxMode.DANGER_FULL_ACCESS) {
            return null;
        }
        return checkWritable(target, workspaceRoot);
    }

    /** 读取内容（带上限防护）。 */
    public static String readText(Path path, long maxBytes) throws IOException {
        long size = Files.size(path);
        if (size > maxBytes) {
            throw new IOException("文件过大: " + size + " bytes > " + maxBytes);
        }
        return Files.readString(path);
    }
}
