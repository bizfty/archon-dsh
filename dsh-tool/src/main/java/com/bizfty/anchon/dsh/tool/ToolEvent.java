package com.bizfty.anchon.dsh.tool;

import java.time.Instant;
import java.util.Map;

/**
 * 工具事件 — 面向 UI/SSE 的轻量事件（工具名/结果/时间）。
 * <p>
 * 会话事件总线上另有完整事件（TOOL_CALL/TOOL_RESULT/…）供持久化与 guard 消费；
 * 本类型是流式 API 直推前端的投影。
 */
public record ToolEvent(
        String eventType,
        String toolName,
        String message,
        boolean success,
        Map<String, Object> data,
        Instant createdAt) {

    public static ToolEvent toolResult(String toolName, boolean success, String message, Map<String, Object> data) {
        return new ToolEvent("tool_result", toolName, message, success, data, Instant.now());
    }

    public static ToolEvent toolError(String toolName, String message, Map<String, Object> data) {
        return new ToolEvent("tool_error", toolName, message, false, data, Instant.now());
    }
}
