package com.example.dsh.guard;

import com.example.dsh.core.model.SessionId;
import com.example.dsh.tool.ToolCall;
import com.example.dsh.tool.ToolContext;
import com.example.dsh.tool.ToolPostProcessor;
import com.example.dsh.tool.ToolResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 重复工具调用提醒 — 连续相同 (tool, 规范化参数) 达到阈值时，通过
 * additionalContexts 注入升级提醒（对应 DSH guard/repeat-tool-reminder）。
 * <p>
 * 提醒不替换 tool/result 内容；由 agent-loop 在工具结果后作为用户消息注入。
 */
@Component
public class RepeatToolReminder implements ToolPostProcessor {

    private final int threshold;

    /** 每会话：toolName → argsKey → 连续次数。 */
    private final Map<SessionId, Map<String, Map<String, Integer>>> chains = new ConcurrentHashMap<>();

    public RepeatToolReminder(@Value("${dsh.guard.repeat-threshold:3}") int threshold) {
        this.threshold = Math.max(2, threshold);
    }

    @Override
    public int order() {
        return 100;
    }

    @Override
    public ToolResult process(ToolCall call, ToolContext context, ToolResult result) {
        if (context.sessionId() == null) {
            return result;
        }
        String argsKey = normalize(call.arguments());
        Map<String, Map<String, Integer>> sessionChain =
                chains.computeIfAbsent(context.sessionId(), k -> new ConcurrentHashMap<>());
        Map<String, Integer> toolChain = sessionChain.computeIfAbsent(call.name(), k -> new ConcurrentHashMap<>());
        int count = toolChain.merge(argsKey, 1, Integer::sum);

        if (count >= threshold) {
            String reminder = "⚠️ 连续第 " + count + " 次以相同参数调用 " + call.name()
                    + "（参数: " + argsKey + "）。请检查是否陷入循环：确认上次结果失败原因，"
                    + "换一种方法或向用户说明，不要重复相同调用。";
            return ToolResult.failure(result.message(), result.data(), withReminder(result, reminder));
        }
        return result;
    }

    private List<String> withReminder(ToolResult result, String reminder) {
        List<String> existing = result.additionalContexts() == null ? List.of() : result.additionalContexts();
        if (existing.contains(reminder)) {
            return existing;
        }
        return java.util.stream.Stream.concat(existing.stream(), java.util.stream.Stream.of(reminder)).toList();
    }

    /** 规范化参数：稳定排序的 key=value 摘要，仅用于重复检测。 */
    private String normalize(Map<String, Object> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return "{}";
        }
        return arguments.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> e.getKey() + "=" + String.valueOf(e.getValue()))
                .reduce((a, b) -> a + "|" + b)
                .orElse("{}");
    }

    /** 会话结束后清理（P2：随会话生命周期）。 */
    public void clear(SessionId sessionId) {
        chains.remove(sessionId);
    }
}
