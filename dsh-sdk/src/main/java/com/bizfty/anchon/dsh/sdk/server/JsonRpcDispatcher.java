package com.bizfty.anchon.dsh.sdk.server;

import com.bizfty.anchon.dsh.agent.AgentLoopService;
import com.bizfty.anchon.dsh.agent.AgentRunRequest;
import com.bizfty.anchon.dsh.agent.AgentRunResult;
import com.bizfty.anchon.dsh.core.model.MessageRole;
import com.bizfty.anchon.dsh.core.model.Session;
import com.bizfty.anchon.dsh.core.model.SessionId;
import com.bizfty.anchon.dsh.core.model.SessionMessage;
import com.bizfty.anchon.dsh.sdk.protocol.JsonRpc;
import com.bizfty.anchon.dsh.session.SessionService;
import com.bizfty.anchon.dsh.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * JSON-RPC 分发器 — 把行分隔的 JSON-RPC 请求映射到 harness 操作
 * （对应 DSH sdk/server 的方法集：initialize / session.prompt / session.list /
 * session.messages / shutdown）。
 */
@Component
public class JsonRpcDispatcher {

    private static final Logger log = LoggerFactory.getLogger(JsonRpcDispatcher.class);
    private static final String PROTOCOL_VERSION = "dsh-java-jsonrpc-1";

    private final AgentLoopService agentLoopService;
    private final SessionService sessionService;
    private final JsonUtils jsonUtils;
    private final AtomicBoolean shutdownRequested = new AtomicBoolean(false);

    public JsonRpcDispatcher(AgentLoopService agentLoopService, SessionService sessionService,
                             JsonUtils jsonUtils) {
        this.agentLoopService = agentLoopService;
        this.sessionService = sessionService;
        this.jsonUtils = jsonUtils;
    }

    public boolean isShutdownRequested() {
        return shutdownRequested.get();
    }

    /**
     * 处理一行输入。
     *
     * @return 要写出的响应行（通知/空行返回 null）
     */
    public String handleLine(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        Object parsed;
        try {
            parsed = jsonUtils.fromJson(line, Object.class);
        } catch (Exception e) {
            return serialize(JsonRpc.Response.fail(null, JsonRpc.PARSE_ERROR, "解析错误", e.getMessage()));
        }
        if (!(parsed instanceof Map<?, ?> map)) {
            return serialize(JsonRpc.Response.fail(null, JsonRpc.INVALID_REQUEST, "非法请求", null));
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> msg = (Map<String, Object>) map;
        String method = msg.get("method") == null ? null : String.valueOf(msg.get("method"));
        Object id = msg.get("id");
        if (method == null) {
            return serialize(JsonRpc.Response.fail(null, JsonRpc.INVALID_REQUEST, "缺少 method", null));
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> params = msg.get("params") instanceof Map<?, ?> p
                ? (Map<String, Object>) p : Map.of();
        if (id == null) {
            // 通知：shutdown 通知也会触发关闭
            if ("shutdown".equals(method)) {
                shutdownRequested.set(true);
            }
            return null;
        }
        String idText = String.valueOf(id);
        try {
            Object result = dispatch(method, params);
            return serialize(JsonRpc.Response.ok(idText, result));
        } catch (MethodNotFoundException e) {
            return serialize(JsonRpc.Response.fail(idText, JsonRpc.METHOD_NOT_FOUND, e.getMessage(), null));
        } catch (IllegalArgumentException e) {
            return serialize(JsonRpc.Response.fail(idText, JsonRpc.INVALID_PARAMS, e.getMessage(), null));
        } catch (Exception e) {
            log.error("[JSON-RPC] {} 失败: {}", method, e.getMessage(), e);
            return serialize(JsonRpc.Response.fail(idText, JsonRpc.INTERNAL_ERROR, e.getMessage(), null));
        }
    }

    private Object dispatch(String method, Map<String, Object> params) {
        return switch (method) {
            case "initialize" -> Map.of("protocolVersion", PROTOCOL_VERSION,
                    "capabilities", Map.of("sessions", true));
            case "session/prompt" -> sessionPrompt(params);
            case "session/list" -> sessionList();
            case "session/messages" -> sessionMessages(params);
            case "session/create" -> sessionCreate(params);
            case "shutdown" -> {
                shutdownRequested.set(true);
                yield Map.of("ok", true);
            }
            default -> throw new MethodNotFoundException("未知方法: " + method);
        };
    }

    private Object sessionPrompt(Map<String, Object> params) {
        String message = params.get("message") == null ? "" : String.valueOf(params.get("message"));
        if (message.isBlank()) {
            throw new IllegalArgumentException("session/prompt 需要 message");
        }
        Session session;
        String sessionId = params.get("session_id") == null ? null : String.valueOf(params.get("session_id"));
        if (sessionId == null || sessionId.isBlank()) {
            session = sessionService.createSession(null, str(params, "model"), null);
        } else {
            session = sessionService.getSession(SessionId.of(sessionId));
        }
        String model = str(params, "model");
        AgentRunResult result = agentLoopService.run(AgentRunRequest.builder()
                .sessionId(session.id())
                .userMessage(message)
                .modelOverride(model)
                .executionId("jsonrpc-" + UUID.randomUUID())
                .build());
        return Map.of("session_id", session.id().value(), "content", result.content(),
                "steps", result.steps(), "tool_calls", result.toolCalls());
    }

    private Object sessionCreate(Map<String, Object> params) {
        Session session = sessionService.createSession(str(params, "title"), str(params, "model"), null);
        return Map.of("session_id", session.id().value());
    }

    private Object sessionList() {
        return sessionService.listSessions().stream()
                .map(s -> Map.of("session_id", s.id().value(), "title", orEmpty(s.title()),
                        "model", orEmpty(s.model())))
                .toList();
    }

    private Object sessionMessages(Map<String, Object> params) {
        String sessionId = params.get("session_id") == null ? null : String.valueOf(params.get("session_id"));
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("session/messages 需要 session_id");
        }
        int limit = params.get("limit") instanceof Number n ? n.intValue() : 100;
        List<SessionMessage> all = sessionService.listMessages(SessionId.of(sessionId));
        int from = Math.max(0, all.size() - limit);
        return all.subList(from, all.size()).stream()
                .map(m -> Map.of("role", m.role().name(), "content", orEmpty(m.content()),
                        "tool", orEmpty(m.toolName())))
                .toList();
    }

    private String serialize(Object value) {
        return jsonUtils.toJson(value);
    }

    private static String str(Map<String, Object> params, String key) {
        Object v = params.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private static String orEmpty(String v) {
        return v == null ? "" : v;
    }

    /** 未知方法。 */
    public static final class MethodNotFoundException extends RuntimeException {
        public MethodNotFoundException(String message) {
            super(message);
        }
    }
}
