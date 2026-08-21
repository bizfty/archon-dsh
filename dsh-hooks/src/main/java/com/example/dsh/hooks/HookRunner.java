package com.example.dsh.hooks;

import com.example.dsh.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Hook 命令执行器（对应 DSH hooks/hook-protocol 的协议面）。
 * <p>
 * 协议（对齐 Claude Code shell-hook）：
 * <ul>
 *   <li>事件 JSON（sessionId/toolName/toolInput/cwd/eventName）写入子进程 stdin；</li>
 *   <li>环境变量 CLAUDE_HOOK_EVENT_NAME / CLAUDE_TOOL_NAME / CLAUDE_TOOL_INPUT_JSON /
 *       CLAUDE_SESSION_ID / CLAUDE_CWD 一并注入；</li>
 *   <li>子进程 stdout 输出决策 JSON：{"decision":"continue|block|ask","reason":...,"question":...}。</li>
 * </ul>
 * 失败语义：非零退出/超时/输出非 JSON → block（安全默认，附原因）；输出无效 → 不静默放行。
 */
@Component
public class HookRunner {

    private static final Logger log = LoggerFactory.getLogger(HookRunner.class);
    private static final long TIMEOUT_MS = 30_000;
    private static final int MAX_OUTPUT_BYTES = 64 * 1024;

    private final JsonUtils jsonUtils;
    private final long timeoutMs;

    public HookRunner(JsonUtils jsonUtils,
                      @Value("${dsh.hooks.timeout-ms:30000}") long timeoutMs) {
        this.jsonUtils = jsonUtils;
        this.timeoutMs = timeoutMs;
    }

    /**
     * 执行 hook 命令并返回决策。
     *
     * @return empty = 放行（decision continue）；否则 = 拒绝原因（block 的 reason / ask 的 question）
     */
    public Optional<String> run(String event, String toolName, Map<String, Object> toolInput,
                                String sessionId, String cwd, String command) {
        try {
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", command);
            if (cwd != null && !cwd.isBlank()) {
                pb.directory(new java.io.File(cwd));
            }
            pb.redirectErrorStream(false);
            Map<String, String> env = pb.environment();
            env.put("CLAUDE_HOOK_EVENT_NAME", event == null ? "" : event);
            env.put("CLAUDE_TOOL_NAME", toolName == null ? "" : toolName);
            env.put("CLAUDE_TOOL_INPUT_JSON", toolInput == null ? "{}" : jsonUtils.toJson(toolInput));
            env.put("CLAUDE_SESSION_ID", sessionId == null ? "" : sessionId);
            env.put("CLAUDE_CWD", cwd == null ? "" : cwd);

            Map<String, Object> payload = new java.util.LinkedHashMap<>();
            payload.put("session_id", sessionId);
            payload.put("tool_name", toolName);
            payload.put("tool_input", toolInput == null ? Map.of() : toolInput);
            payload.put("cwd", cwd);
            payload.put("hook_event_name", event);

            Process process = pb.start();
            // stdin 负载为尽力而为：hook 可能不读 stdin 并立即退出（快速命令的竞态）。
            // 事件数据同时经环境变量 CLAUDE_* 注入，不依赖 stdin 送达。
            try {
                process.getOutputStream().write(jsonUtils.toJson(payload).getBytes(StandardCharsets.UTF_8));
                process.getOutputStream().close();
            } catch (IOException ignored) {
                // hook 未读 stdin 即退出：忽略（数据已在环境变量中）
            }
            boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.warn("[Hooks] {} {} 超时（{}ms）→ block", event, toolName, timeoutMs);
                return Optional.of("hook 超时: " + command);
            }
            String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (stdout.length() > MAX_OUTPUT_BYTES) {
                stdout = stdout.substring(0, MAX_OUTPUT_BYTES);
            }
            if (process.exitValue() != 0) {
                log.warn("[Hooks] {} {} 退出码 {} → block", event, toolName, process.exitValue());
                return Optional.of("hook 退出码非零: " + command);
            }
            return parseDecision(stdout, event, toolName, command);
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[Hooks] {} {} 执行异常 → block: {}", event, toolName, e.getMessage());
            return Optional.of("hook 执行异常: " + e.getMessage());
        }
    }

    /** 解析决策输出：continue → 放行；block → reason；ask → question（映射为需询问的拒绝）。 */
    private Optional<String> parseDecision(String stdout, String event, String toolName, String command) {
        String trimmed = stdout == null ? "" : stdout.trim();
        if (trimmed.isEmpty()) {
            log.warn("[Hooks] {} {} 输出为空 → block", event, toolName);
            return Optional.of("hook 输出为空: " + command);
        }
        try {
            Map<String, Object> decision = jsonUtils.toMap(trimmed);
            String kind = String.valueOf(decision.getOrDefault("decision", ""));
            switch (kind) {
                case "continue" -> {
                    return Optional.empty();
                }
                case "block" -> {
                    String reason = String.valueOf(decision.getOrDefault("reason", "hook 拒绝"));
                    return Optional.of(reason);
                }
                case "ask" -> {
                    String question = String.valueOf(decision.getOrDefault("question", "hook 要求询问用户"));
                    return Optional.of("hook 要求询问用户: " + question + "（请用 ask_user_question 询问后继续）");
                }
                default -> {
                    log.warn("[Hooks] {} {} 决策未知 {} → block", event, toolName, kind);
                    return Optional.of("hook 返回未知决策: " + kind);
                }
            }
        } catch (RuntimeException e) {
            log.warn("[Hooks] {} {} 决策非 JSON → block: {}", event, toolName, e.getMessage());
            return Optional.of("hook 输出无效（非 JSON）: " + command);
        }
    }
}
