package com.bizfty.anchon.dsh.tool;

import java.util.List;
import java.util.Map;

/**
 * 工具执行结果 — 模型可见的最终内容。
 * <p>
 * 对应 DSH ToolExecutionResult 的模型面（content + isError + additionalContexts）；
 * 序列化为 {"success": bool, "message": str, "data": {...}}。
 * additionalContexts 是工具/后处理器注入的附加用户消息（如重复调用提醒、
 * 嵌套分发上下文），由 agent-loop 在工具结果之后逐条注入 — 不进结果 JSON。
 */
public record ToolResult(
        boolean success,
        String message,
        Map<String, Object> data,
        List<String> additionalContexts) {

    public static ToolResult success(String message) {
        return new ToolResult(true, message, Map.of(), List.of());
    }

    public static ToolResult success(String message, Map<String, Object> data) {
        return new ToolResult(true, message, data, List.of());
    }

    public static ToolResult success(String message, Map<String, Object> data, List<String> additionalContexts) {
        return new ToolResult(true, message, data, additionalContexts);
    }

    public static ToolResult failure(String message) {
        return new ToolResult(false, message, Map.of(), List.of());
    }

    public static ToolResult failure(String message, Exception cause) {
        return new ToolResult(false, message + (cause == null ? "" : " — " + cause.getMessage()), Map.of(), List.of());
    }

    public static ToolResult failure(String message, Map<String, Object> data) {
        return new ToolResult(false, message, data, List.of());
    }

    public static ToolResult failure(String message, Map<String, Object> data, List<String> additionalContexts) {
        return new ToolResult(false, message, data, additionalContexts);
    }

    /** 带附加上下文的失败结果。 */
    public static ToolResult failure(String message, List<String> additionalContexts) {
        return new ToolResult(false, message, Map.of(), additionalContexts);
    }

    /** 序列化视图（AgentToolCallback 的返回体；不含 additionalContexts）。 */
    public Map<String, Object> toMap() {
        return Map.of(
                "success", success,
                "message", message == null ? "" : message,
                "data", data == null ? Map.of() : data);
    }
}
