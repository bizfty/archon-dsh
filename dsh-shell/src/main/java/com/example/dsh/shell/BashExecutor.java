package com.example.dsh.shell;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Bash 执行器 — 每次调用新进程（对应 DSH shell/bash-local）。
 * <p>
 * 模型友好环境：NO_COLOR、TERM=dumb；超时强制杀进程树。
 */
@Component
public class BashExecutor {

    /** 执行结果。 */
    public record BashResult(boolean success, String output, int exitCode, boolean timedOut, long elapsedMs) {
        /** 模型可见文本（对应 DSH [exit code: N] 渲染）。 */
        public String toDisplayText() {
            StringBuilder sb = new StringBuilder();
            if (output != null && !output.isBlank()) {
                sb.append(output);
                if (!output.endsWith("\n")) {
                    sb.append('\n');
                }
            }
            if (timedOut) {
                sb.append("[timed out after ").append(elapsedMs).append(" ms]\n");
            }
            sb.append("[exit code: ").append(exitCode).append("]");
            return sb.toString();
        }
    }

    /**
     * 执行命令。
     *
     * @param command     shell 命令文本
     * @param workdir     工作目录（可为 null → 会话 cwd）
     * @param timeoutMs   超时毫秒（<=0 表示默认 60s）
     */
    public BashResult run(String command, String workdir, long timeoutMs) {
        return run(command, workdir, timeoutMs, Map.of());
    }

    /**
     * 执行命令（带受管环境变量，对应 DSH shell-env：DSH_SESSION_ID 等注入子进程）。
     */
    public BashResult run(String command, String workdir, long timeoutMs, Map<String, String> extraEnv) {
        long effectiveTimeout = timeoutMs > 0 ? timeoutMs : 60_000;
        ProcessBuilder pb = new ProcessBuilder("/bin/bash", "-c", command);
        pb.environment().put("NO_COLOR", "1");
        pb.environment().put("TERM", "dumb");
        if (extraEnv != null) {
            pb.environment().putAll(extraEnv);
        }
        Path dir = workdir == null || workdir.isBlank() ? Paths.get("").toAbsolutePath() : Paths.get(workdir);
        if (Files.isDirectory(dir)) {
            pb.directory(dir.toFile());
        }
        pb.redirectErrorStream(true);
        long start = System.currentTimeMillis();
        try {
            Process process = pb.start();
            boolean finished = process.waitFor(effectiveTimeout, TimeUnit.MILLISECONDS);
            long elapsed = System.currentTimeMillis() - start;
            if (!finished) {
                killTree(process);
                process.destroyForcibly();
                return new BashResult(false, "命令超时被终止", -1, true, elapsed);
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exit = process.exitValue();
            return new BashResult(exit == 0, output, exit, false, elapsed);
        } catch (IOException e) {
            return new BashResult(false, "启动失败: " + e.getMessage(), -1, false, 0);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new BashResult(false, "被中断", -1, false, 0);
        }
    }

    private void killTree(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
    }
}
