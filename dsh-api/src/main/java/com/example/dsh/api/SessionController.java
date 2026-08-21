package com.example.dsh.api;

import com.example.dsh.agent.AgentLoopService;
import com.example.dsh.agent.AgentRunRequest;
import com.example.dsh.agent.AgentRunResult;
import com.example.dsh.api.dto.ChatRequest;
import com.example.dsh.api.dto.MessageDto;
import com.example.dsh.api.dto.SessionDto;
import com.example.dsh.core.event.SessionEventBus;
import com.example.dsh.core.event.SessionEventType;
import com.example.dsh.core.model.MessageRole;
import com.example.dsh.core.model.Session;
import com.example.dsh.core.model.SessionId;
import com.example.dsh.core.model.SessionMessage;
import com.example.dsh.session.SessionService;
import com.example.dsh.tool.ToolEvent;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 会话 API — CRUD + chat + SSE 流式（对应 DSH api/gateway 的业务 API 面）。
 */
@RestController
@RequestMapping("/api/sessions")
public class SessionController {

    private final SessionService sessionService;
    private final AgentLoopService agentLoopService;
    private final SessionEventBus eventBus;

    public SessionController(SessionService sessionService, AgentLoopService agentLoopService,
                             SessionEventBus eventBus) {
        this.sessionService = sessionService;
        this.agentLoopService = agentLoopService;
        this.eventBus = eventBus;
    }

    @GetMapping
    public List<SessionDto> listSessions() {
        return sessionService.listSessions().stream().map(this::toDto).toList();
    }

    @PostMapping
    public ResponseEntity<SessionDto> createSession(@RequestBody(required = false) CreateSessionRequest request) {
        String title = request == null ? null : request.title();
        String model = request == null ? null : request.model();
        String cwd = request == null ? null : request.cwd();
        Session session = sessionService.createSession(title, model, cwd);
        return ResponseEntity.status(HttpStatus.CREATED).body(toDto(session));
    }

    @GetMapping("/{sessionId}")
    public SessionDto getSession(@PathVariable String sessionId) {
        return toDto(sessionService.getSession(SessionId.of(sessionId)));
    }

    @DeleteMapping("/{sessionId}")
    public ResponseEntity<Void> deleteSession(@PathVariable String sessionId) {
        // P1: 级联删除消息
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{sessionId}")
    public SessionDto updateSession(@PathVariable String sessionId, @RequestBody UpdateSessionRequest request) {
        SessionId id = SessionId.of(sessionId);
        Session session = sessionService.getSession(id);
        if (request.model() != null) {
            session = sessionService.updateModel(id, request.model());
        }
        if (request.title() != null) {
            session = sessionService.updateTitle(id, request.title());
        }
        return toDto(session);
    }

    @GetMapping("/{sessionId}/messages")
    public List<MessageDto> messages(@PathVariable String sessionId,
                                     @RequestParam(defaultValue = "100") int limit) {
        List<SessionMessage> all = sessionService.listMessages(SessionId.of(sessionId));
        int from = Math.max(0, all.size() - limit);
        return all.subList(from, all.size()).stream()
                .map(m -> MessageDto.simple(m.id(), m.role().name().toLowerCase(), m.content()))
                .toList();
    }

    @PostMapping(value = "/{sessionId}/chat", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<MessageDto> chat(@PathVariable String sessionId, @Valid @RequestBody ChatRequest request) {
        Session session = sessionService.getSession(SessionId.of(sessionId));
        String commandResult = tryCommand(session, request.message());
        if (commandResult != null) {
            return ResponseEntity.ok(MessageDto.simple("msg-" + UUID.randomUUID(), "assistant", commandResult));
        }
        AgentRunRequest runRequest = AgentRunRequest.builder()
                .sessionId(session.id())
                .userMessage(request.message())
                .modelOverride(request.model() != null ? request.model() : session.model())
                .agentId(request.agentId())
                .build();
        AgentRunResult result = agentLoopService.run(runRequest);
        return ResponseEntity.ok(MessageDto.simple("msg-" + UUID.randomUUID(), "assistant", result.content()));
    }

    /**
     * 人类命令拦截（对应 DSH command 面）：当前支持 `/compact`（手动压缩 —
     * 不经过模型 turn，也不写入会话日志）。
     *
     * @return 命令结果文本；非命令返回 null（走正常 agent 流程）
     */
    private String tryCommand(Session session, String message) {
        if (message == null) {
            return null;
        }
        String trimmed = message.trim();
        if (trimmed.equals("/compact")) {
            return agentLoopService.manualCompact(session.id());
        }
        if (trimmed.startsWith("/compact ")) {
            return "Usage: /compact (no arguments)";
        }
        return null;
    }

    @PostMapping(value = "/{sessionId}/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@PathVariable String sessionId, @Valid @RequestBody ChatRequest request) {
        SseEmitter emitter = new SseEmitter(300_000L);
        Session session = sessionService.getSession(SessionId.of(sessionId));
        String commandResult = tryCommand(session, request.message());
        if (commandResult != null) {
            // 命令不走模型流：单条 message 事件 + done
            try {
                emitter.send(SseEmitter.event().name("message").data(Map.of("content", commandResult), MediaType.APPLICATION_JSON));
                emitter.send(SseEmitter.event().name("done").data(Map.of("ok", true), MediaType.APPLICATION_JSON));
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
            emitter.complete();
            return emitter;
        }
        String executionId = "sse-" + UUID.randomUUID();
        AgentRunRequest runRequest = AgentRunRequest.builder()
                .sessionId(session.id())
                .userMessage(request.message())
                .modelOverride(request.model() != null ? request.model() : session.model())
                .agentId(request.agentId())
                .executionId(executionId)
                .build();
        // 审批联动：本执行流的 APPROVAL_REQUESTED 事件推为 SSE tool 事件
        // （模型在审批门阻塞时，用户看到"待审批"通知，经 REST 端点应答后恢复）
        Runnable unsubscribe = eventBus.addListener(event -> {
            if (event.type() == SessionEventType.APPROVAL_REQUESTED
                    && executionId.equals(event.string("executionId"))) {
                sendSse(emitter, "tool", Map.of(
                        "event_type", "approval_requested",
                        "tool", event.string("tool"),
                        "reason", event.string("reason")));
            }
            // 问答联动：ask_user_question 阻塞时推 question 事件（按事件自带的 sessionId 匹配），
            // 前端渲染选择框，用户应答（POST /questions/{id}/answer）后工具继续
            if (event.type() == SessionEventType.QUESTION_REQUESTED
                    && session.id().value().equals(event.sessionId().value())) {
                sendSse(emitter, "question", Map.of(
                        "question", event.string("question"),
                        "options", event.get("options") == null ? List.of() : event.get("options"),
                        "multiSelect", Boolean.TRUE.equals(event.get("multiSelect"))));
            }
        });
        Thread.startVirtualThread(() -> {
            try {
                agentLoopService.stream(runRequest,
                        token -> sendSse(emitter, "message", Map.of("content", token)),
                        toolEvent -> sendSse(emitter, "tool",
                                Map.of("tool", toolEvent.toolName(), "event_type", toolEvent.eventType(),
                                        "success", toolEvent.success(), "message", toolEvent.message())));
                sendSse(emitter, "done", Map.of("ok", true));
                emitter.complete();
            } catch (Exception e) {
                try {
                    sendSse(emitter, "error", Map.of("message", e.getMessage()));
                    emitter.complete();
                } catch (Exception ignored) {
                    emitter.completeWithError(e);
                }
            } finally {
                unsubscribe.run();
            }
        });
        return emitter;
    }

    private void sendSse(SseEmitter emitter, String event, Object data) {
        try {
            // 必须显式指定 JSON mediaType：SseEmitter 默认 text/event-stream 无 JSON converter
            // （曾抛 HttpMessageNotWritableException: No converter for MapN）
            emitter.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON));
        } catch (Exception e) {
            throw new RuntimeException("SSE 发送失败", e);
        }
    }

    private SessionDto toDto(Session session) {
        return new SessionDto(session.id().value(), session.title(), session.model(), session.cwd(),
                "active", session.createdAt(), session.updatedAt());
    }

    public record CreateSessionRequest(String title, String model, String cwd) {
    }

    public record UpdateSessionRequest(String title, String model) {
    }
}
