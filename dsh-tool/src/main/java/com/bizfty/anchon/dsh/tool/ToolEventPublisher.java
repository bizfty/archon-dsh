package com.bizfty.anchon.dsh.tool;

import com.bizfty.anchon.dsh.core.event.SessionEvent;
import com.bizfty.anchon.dsh.core.event.SessionEventBus;
import com.bizfty.anchon.dsh.core.event.SessionEventType;
import com.bizfty.anchon.dsh.util.JsonUtils;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工具事件发布器 — 把工具生命周期发布到会话事件总线
 * （TOOL_CALL / TOOL_RESULT / TOOL_DENIED / TOOL_ERROR），
 * 供持久化、UI（SSE）与 guard 消费。
 */
@Component
public class ToolEventPublisher {

    private final SessionEventBus eventBus;
    private final JsonUtils jsonUtils;

    public ToolEventPublisher(SessionEventBus eventBus, JsonUtils jsonUtils) {
        this.eventBus = eventBus;
        this.jsonUtils = jsonUtils;
    }

    public void publishToolCall(ToolCall call, ToolContext context) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tool", call.name());
        payload.put("callId", call.id());
        payload.put("args", summarize(call.arguments()));
        if (context.executionId() != null) {
            payload.put("executionId", context.executionId());
        }
        eventBus.publish(context.sessionId(), SessionEventType.TOOL_CALL, payload);
    }

    public void publishToolResult(ToolCall call, ToolContext context, ToolResult result) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tool", call.name());
        payload.put("callId", call.id());
        payload.put("success", result.success());
        payload.put("message", result.message());
        payload.put("data", result.data());
        if (context.executionId() != null) {
            payload.put("executionId", context.executionId());
        }
        eventBus.publish(context.sessionId(),
                result.success() ? SessionEventType.TOOL_RESULT : SessionEventType.TOOL_ERROR, payload);
    }

    public void publishToolDenied(ToolCall call, ToolContext context, String reason) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tool", call.name());
        payload.put("callId", call.id());
        payload.put("reason", reason);
        if (context.executionId() != null) {
            payload.put("executionId", context.executionId());
        }
        eventBus.publish(context.sessionId(), SessionEventType.TOOL_DENIED, payload);
    }

    public void publishToolTimeout(ToolCall call, ToolContext context, long timeoutMs) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tool", call.name());
        payload.put("callId", call.id());
        payload.put("timeoutMs", timeoutMs);
        if (context.executionId() != null) {
            payload.put("executionId", context.executionId());
        }
        eventBus.publish(context.sessionId(), SessionEventType.TOOL_TIMEOUT, payload);
    }

    /** 参数摘要（防敏感/超长参数直接进事件 payload）。 */
    private Map<String, Object> summarize(Map<String, Object> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : arguments.entrySet()) {
            Object v = e.getValue();
            if (v instanceof String s && s.length() > 80) {
                summary.put(e.getKey(), s.substring(0, 80) + "...");
            } else {
                summary.put(e.getKey(), v);
            }
        }
        return summary;
    }

    /** 供外部读取最新事件的便捷方法（测试/UI 用）。 */
    public SessionEventBus eventBus() {
        return eventBus;
    }
}
