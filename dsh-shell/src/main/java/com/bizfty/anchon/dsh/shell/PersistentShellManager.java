package com.bizfty.anchon.dsh.shell;

import com.bizfty.anchon.dsh.core.model.SessionId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 持久 shell 管理器 — 每会话一个长驻 bash 进程（管道传输）。
 * <p>
 * 状态（cwd/export/函数/后台作业）跨调用保留，因为始终是同一个 shell 进程；
 * 对应 DSH terminal 的能力面。无 TTY（交互式程序受限）— 真实 PTY（pty4j+jtermios）
 * 因依赖不在本地仓库标 P2。
 * 协议：写入命令 + 随机哨兵 echo，读取 stdout 直到哨兵出现（非交互 bash 不回显命令）。
 */
@Component
public class PersistentShellManager {

    private static final Logger log = LoggerFactory.getLogger(PersistentShellManager.class);

    private final java.util.Map<SessionId, ShellSession> sessions = new ConcurrentHashMap<>();
    private final long maxOutputBytes;
    private final long defaultTimeoutMs;

    public PersistentShellManager(@Value("${dsh.shell.persistent.max-output-bytes:524288}") long maxOutputBytes,
                                  @Value("${dsh.shell.persistent.timeout-ms:60000}") long defaultTimeoutMs) {
        this.maxOutputBytes = maxOutputBytes;
        this.defaultTimeoutMs = defaultTimeoutMs;
    }

    /**
     * 在会话的持久 shell 中执行命令。
     */
    public ShellResult execute(SessionId sessionId, String command, long timeoutMs) {
        ShellSession shell = sessions.computeIfAbsent(sessionId, k -> startShell(sessionId));
        return shell.execute(command, timeoutMs > 0 ? timeoutMs : defaultTimeoutMs);
    }

    public void close(SessionId sessionId) {
        ShellSession shell = sessions.remove(sessionId);
        if (shell != null) {
            shell.destroy();
        }
    }

    public int activeSessions() {
        return sessions.size();
    }

    private ShellSession startShell(SessionId sessionId) {
        try {
            ProcessBuilder pb = new ProcessBuilder("/bin/bash", "--norc", "--noprofile", "-s");
            pb.environment().put("NO_COLOR", "1");
            pb.environment().put("TERM", "dumb");
            if (sessionId != null) {
                pb.environment().put("DSH_SESSION_ID", sessionId.value());
            }
            Process process = pb.start();
            return new ShellSession(process, maxOutputBytes);
        } catch (IOException e) {
            throw new IllegalStateException("无法启动持久 shell: " + e.getMessage(), e);
        }
    }

    /** 执行结果。 */
    public record ShellResult(boolean success, String output, boolean timedOut) {
    }

    /** 单会话 shell 封装（同步执行一个命令）。 */
    private static final class ShellSession {

        private final Process process;
        private final BufferedReader stdout;
        private final Writer stdin;
        private final long maxOutputBytes;
        private boolean dead;

        ShellSession(Process process, long maxOutputBytes) {
            this.process = process;
            this.stdout = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            this.stdin = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
            this.maxOutputBytes = maxOutputBytes;
        }

        synchronized ShellResult execute(String command, long timeoutMs) {
            if (dead || !process.isAlive()) {
                throw new IllegalStateException("持久 shell 已退出，请重新创建会话");
            }
            String sentinel = "__DSH_DONE_" + UUID.randomUUID().toString().substring(0, 8) + "__";
            StringBuilder output = new StringBuilder();
            boolean timedOut = false;
            long deadline = System.currentTimeMillis() + timeoutMs;
            try {
                stdin.write(command + "\n");
                stdin.write("echo " + sentinel + "\n");
                stdin.flush();
                String line;
                while ((line = stdout.readLine()) != null) {
                    if (line.contains(sentinel)) {
                        break;
                    }
                    output.append(line).append('\n');
                    if (output.length() > maxOutputBytes) {
                        output.setLength(0);
                        output.append("[输出超限截断]");
                        break;
                    }
                    if (System.currentTimeMillis() > deadline) {
                        timedOut = true;
                        break;
                    }
                }
            } catch (IOException e) {
                dead = true;
                destroy();
                throw new IllegalStateException("持久 shell 通信失败: " + e.getMessage(), e);
            }
            if (timedOut) {
                destroy();
                dead = true;
                return new ShellResult(false, "[持久 shell 命令超时，已重置]\n" + output, true);
            }
            return new ShellResult(true, output.toString().trim(), false);
        }

        void destroy() {
            try {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            } catch (Exception ignored) {
            }
        }
    }
}
