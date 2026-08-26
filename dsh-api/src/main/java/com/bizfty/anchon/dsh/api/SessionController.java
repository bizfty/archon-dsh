package com.bizfty.anchon.dsh.api;

import com.bizfty.anchon.dsh.agent.AgentLoopService;
import com.bizfty.anchon.dsh.agent.AgentRunRequest;
import com.bizfty.anchon.dsh.agent.AgentRunResult;
import com.bizfty.anchon.dsh.api.dto.ChatRequest;
import com.bizfty.anchon.dsh.api.dto.MessageDto;
import com.bizfty.anchon.dsh.api.dto.SessionDto;
import com.bizfty.anchon.dsh.core.event.SessionEventBus;
import com.bizfty.anchon.dsh.core.event.SessionEventType;
import com.bizfty.anchon.dsh.core.model.MessageRole;
import com.bizfty.anchon.dsh.core.model.Session;
import com.bizfty.anchon.dsh.core.model.SessionId;
import com.bizfty.anchon.dsh.core.model.SessionMessage;
import com.bizfty.anchon.dsh.feedback.FeedbackService;
import com.bizfty.anchon.dsh.llm.LlmAuthException;
import com.bizfty.anchon.dsh.session.SessionService;
import com.bizfty.anchon.dsh.subagent.SubagentHandle;
import com.bizfty.anchon.dsh.subagent.SubagentRegistry;
import com.bizfty.anchon.dsh.subagent.SubagentRunner;
import com.bizfty.anchon.dsh.tool.ToolEvent;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpHeaders;
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
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * 会话 API — CRUD + chat + SSE 流式（对应 DSH api/gateway 的业务 API 面）。
 */
@RestController
@RequestMapping("/api/sessions")
public class SessionController {

    private final SessionService sessionService;
    private final AgentLoopService agentLoopService;
    private final SessionEventBus eventBus;
    private final FeedbackService feedbackService;
    private final SseExecutionStore executionStore;
    private final SubagentRegistry subagentRegistry;
    private final SubagentRunner subagentRunner;

    public SessionController(SessionService sessionService, AgentLoopService agentLoopService,
                             SessionEventBus eventBus, FeedbackService feedbackService,
                             SseExecutionStore executionStore,
                             SubagentRegistry subagentRegistry, SubagentRunner subagentRunner) {
        this.sessionService = sessionService;
        this.agentLoopService = agentLoopService;
        this.eventBus = eventBus;
        this.feedbackService = feedbackService;
        this.executionStore = executionStore;
        this.subagentRegistry = subagentRegistry;
        this.subagentRunner = subagentRunner;
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

    /**
     * 会话的子代理列表（对齐官方会话头部的子代理展示）。
     * 每个子代理 = 一个持久子会话；前端据此在 chat 顶部渲染子代理卡片。
     */
    @GetMapping("/{sessionId}/subagents")
    public List<Map<String, Object>> subagents(@PathVariable String sessionId) {
        List<SubagentHandle> handles = subagentRegistry.list(SessionId.of(sessionId));
        return handles.stream().map(h -> {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("id", h.id());
            m.put("sessionId", h.sessionId().value());
            m.put("delegationDepth", h.delegationDepth());
            m.put("status", h.status().name());
            m.put("lastContent", h.lastContent());
            m.put("createdAt", h.createdAt() == null ? null : h.createdAt().toString());
            return m;
        }).toList();
    }

    /** 向子代理发送消息（继续同一子会话对话；子会话消息经 GET /{childSessionId}/messages 读取）。 */
    @PostMapping("/{sessionId}/subagents/{childId}/message")
    public ResponseEntity<Map<String, Object>> subagentMessage(@PathVariable String sessionId,
                                                               @PathVariable String childId,
                                                               @RequestBody Map<String, String> body) {
        String message = body == null ? null : body.get("message");
        if (message == null || message.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "bad_request", "message", "message 不能为空"));
        }
        java.util.Optional<String> reply = subagentRunner.followup(SessionId.of(sessionId), childId, message);
        if (reply.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of("childId", childId, "reply", reply.get()));
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
    public SseEmitter chatStream(@PathVariable String sessionId, @Valid @RequestBody ChatRequest request,
                                 @RequestParam(value = "resume", required = false) String resumeExecutionId) {
        // 长 turn 不设硬超时（0 = 不超时），配心跳防代理空闲断连
        SseEmitter emitter = new SseEmitter(0L);
        java.util.concurrent.ScheduledExecutorService heartbeat = heartbeat(emitter);

        // 断线续流：同一 executionId 重连，重放已发生事件 + 续推实时事件
        if (resumeExecutionId != null && !resumeExecutionId.isBlank()) {
            return resumeStream(emitter, heartbeat, resumeExecutionId);
        }

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
            heartbeat.shutdownNow();
            return emitter;
        }
        String executionId = "sse-" + UUID.randomUUID();
        SseExecutionStore.Execution execution = executionStore.begin(executionId);
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
                Map<String, Object> data = Map.of(
                        "event_type", "approval_requested",
                        "tool", event.string("tool"),
                        "reason", event.string("reason"));
                execution.append("tool", data);
                sendSse(emitter, "tool", data);
            }
            // 问答联动：ask_user_question 阻塞时推 question 事件（按事件自带的 sessionId 匹配），
            // 前端渲染选择框，用户应答（POST /questions/{id}/answer）后工具继续
            if (event.type() == SessionEventType.QUESTION_REQUESTED
                    && session.id().value().equals(event.sessionId().value())) {
                Map<String, Object> data = Map.of(
                        "question", event.string("question"),
                        "options", event.get("options") == null ? List.of() : event.get("options"),
                        "multiSelect", Boolean.TRUE.equals(event.get("multiSelect")));
                execution.append("question", data);
                sendSse(emitter, "question", data);
            }
        });
        Thread.startVirtualThread(() -> {
            try {
                agentLoopService.stream(runRequest,
                        token -> {
                            execution.append("message", Map.of("content", token));
                            sendSse(emitter, "message", Map.of("content", token));
                        },
                        toolEvent -> {
                            Map<String, Object> data = Map.of(
                                    "tool", toolEvent.toolName(), "event_type", toolEvent.eventType(),
                                    "success", toolEvent.success(), "message", toolEvent.message());
                            execution.append("tool", data);
                            sendSse(emitter, "tool", data);
                        });
                execution.finish();
                sendSse(emitter, "done", Map.of("ok", true));
                emitter.complete();
            } catch (LlmAuthException e) {
                execution.finish();
                try {
                    sendSse(emitter, "error", Map.of(
                            "error_type", "auth_failed",
                            "message", "LLM 认证失败，请检查 API Key 是否有效",
                            "detail", e.getMessage()));
                    emitter.complete();
                } catch (Exception ignored) {
                    emitter.completeWithError(e);
                }
            } catch (Exception e) {
                execution.finish();
                try {
                    sendSse(emitter, "error", Map.of(
                            "error_type", "internal",
                            "message", e.getMessage()));
                    emitter.complete();
                } catch (Exception ignored) {
                    emitter.completeWithError(e);
                }
            } finally {
                heartbeat.shutdownNow();
                unsubscribe.run();
            }
        });
        return emitter;
    }

    /**
     * 断线续流：以同一 executionId 重连。执行仍在运行 → 重放快照 + 注册续传订阅
     * （后续事件实时推送，含 done）；执行已完成 → 重放完整快照 + done 后清理。
     * 未知 executionId → 返回错误事件（前端可回退为重新发送）。
     */
    private SseEmitter resumeStream(SseEmitter emitter,
                                    java.util.concurrent.ScheduledExecutorService heartbeat,
                                    String executionId) {
        SseExecutionStore.Execution execution = executionStore.get(executionId);
        if (execution == null) {
            try {
                sendSse(emitter, "error", Map.of("message", "执行不存在或已过期: " + executionId));
                sendSse(emitter, "done", Map.of("ok", false));
                emitter.complete();
            } catch (Exception e) {
                emitter.completeWithError(e);
            } finally {
                heartbeat.shutdownNow();
            }
            return emitter;
        }
        // 快照 + 订阅在同一锁内完成，避免窗口丢失
        List<SseExecutionStore.Event> snapshot = execution.snapshotAndSubscribe(event -> {
            try {
                sendSse(emitter, event.name, event.data);
            } catch (Exception e) {
                throw new RuntimeException("续流发送失败", e);
            }
        });
        for (SseExecutionStore.Event event : snapshot) {
            try {
                sendSse(emitter, event.name, event.data);
            } catch (Exception e) {
                heartbeat.shutdownNow();
                emitter.completeWithError(e);
                return emitter;
            }
        }
        if (!execution.isRunning()) {
            // 重连时执行已完成：快照里已含最终事件，补 done 后清理
            try {
                sendSse(emitter, "done", Map.of("ok", true));
                emitter.complete();
            } catch (Exception e) {
                emitter.completeWithError(e);
            } finally {
                heartbeat.shutdownNow();
                executionStore.remove(executionId);
            }
            return emitter;
        }
        // 执行仍在运行：注册完成回调，finish 时推 done 并清理
        execution.onFinish(() -> {
            try {
                sendSse(emitter, "done", Map.of("ok", true));
                emitter.complete();
            } catch (Exception e) {
                emitter.completeWithError(e);
            } finally {
                heartbeat.shutdownNow();
                executionStore.remove(executionId);
            }
        });
        return emitter;
    }

    /** SSE 心跳：每 15 秒一条 comment，防中间代理空闲断开；连接断则自停。 */
    private static java.util.concurrent.ScheduledExecutorService heartbeat(SseEmitter emitter) {
        java.util.concurrent.ScheduledExecutorService scheduler =
                java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "sse-heartbeat");
                    t.setDaemon(true);
                    return t;
                });
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                emitter.send(SseEmitter.event().comment("heartbeat"));
            } catch (Exception e) {
                scheduler.shutdownNow();
            }
        }, 15, 15, java.util.concurrent.TimeUnit.SECONDS);
        return scheduler;
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

    /**
     * 手动压缩会话历史 — 压缩器命令不经过模型 turn，直接走 compaction 服务。
     * 对应 DSH compact 命令（manual-compact 分支）。
     */
    @PostMapping("/{sessionId}/compact")
    public ResponseEntity<Map<String, Object>> compact(@PathVariable String sessionId) {
        Session session = sessionService.getSession(SessionId.of(sessionId));
        String result = agentLoopService.manualCompact(session.id());
        int boundary = agentLoopService.compactionBoundary(session.id());
        List<SessionMessage> history = sessionService.listMessages(session.id());
        int shadowedCount = Math.min(boundary, history.size());
        long estimatedTokens = 0;
        for (int i = 0; i < shadowedCount; i++) {
            SessionMessage m = history.get(i);
            long chars = m.content() == null ? 0 : m.content().length();
            estimatedTokens += chars / 4L + 4;
        }
        return ResponseEntity.ok(Map.of(
                "summary", result,
                "shadowedCount", shadowedCount,
                "estimatedTokens", estimatedTokens
        ));
    }

    /**
     * 导出会话为 ZIP 归档 — 包含会话元数据、所有消息、反馈记录。
     */
    @GetMapping("/{sessionId}/export")
    public ResponseEntity<Resource> export(@PathVariable String sessionId) {
        SessionId id = SessionId.of(sessionId);
        Session session = sessionService.getSession(id);
        List<SessionMessage> messages = sessionService.listMessages(id);

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             ZipOutputStream zos = new ZipOutputStream(baos)) {

            String metaJson = """
                    {
                      "sessionId": "%s",
                      "title": "%s",
                      "model": "%s",
                      "cwd": "%s",
                      "createdAt": "%s",
                      "exportedAt": "%s",
                      "messageCount": %d
                    }
                    """.formatted(
                    session.id().value(),
                    escapeJson(session.title()),
                    escapeJson(session.model() != null ? session.model() : ""),
                    escapeJson(session.cwd() != null ? session.cwd() : ""),
                    session.createdAt(),
                    Instant.now(),
                    messages.size()
            );
            zos.putNextEntry(new ZipEntry("session.json"));
            zos.write(metaJson.getBytes());
            zos.closeEntry();

            StringBuilder md = new StringBuilder();
            md.append("# ").append(session.title()).append("\n\n");
            md.append("**会话 ID:** ").append(session.id().value()).append("\n\n");
            md.append("**模型:** ").append(session.model() != null ? session.model() : "N/A").append("\n\n");
            md.append("**导出时间:** ").append(Instant.now()).append("\n\n");
            md.append("---\n\n");
            for (SessionMessage m : messages) {
                String roleEmoji = switch (m.role()) {
                    case USER -> "👤";
                    case ASSISTANT -> "🤖";
                    case TOOL -> "🔧";
                    case SYSTEM -> "⚙️";
                };
                md.append("## ").append(roleEmoji).append(" ").append(m.role()).append("\n\n");
                if (m.content() != null) {
                    md.append(m.content()).append("\n\n");
                }
                if (m.toolCallsJson() != null) {
                    md.append("```json\n").append(m.toolCallsJson()).append("\n```\n\n");
                }
                md.append("---\n\n");
            }
            zos.putNextEntry(new ZipEntry("conversation.md"));
            zos.write(md.toString().getBytes());
            zos.closeEntry();

            String timestamp = Instant.now().toString().replace(":", "-");
            String filename = "session-%s-%s.zip".formatted(session.id().value(), timestamp);

            byte[] zipBytes = baos.toByteArray();
            ByteArrayResource resource = new ByteArrayResource(zipBytes);

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                    .contentType(MediaType.parseMediaType("application/zip"))
                    .contentLength(zipBytes.length)
                    .body(resource);

        } catch (IOException e) {
            return ResponseEntity.internalServerError()
                    .body(new ByteArrayResource(("Export failed: " + e.getMessage()).getBytes()));
        }
    }

    /**
     * 会话反馈 — 前端 feedback 命令的提交端点。
     */
    @PostMapping("/{sessionId}/feedback")
    public ResponseEntity<Map<String, Object>> submitFeedback(
            @PathVariable String sessionId,
            @RequestBody SubmitFeedbackRequest request) {
        try {
            var record = feedbackService.record(
                    SessionId.of(sessionId),
                    request.messageId(),
                    request.rating(),
                    request.text()
            );
            return ResponseEntity.ok(Map.of("feedback_id", record.id()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    private SessionDto toDto(Session session) {
        return new SessionDto(session.id().value(), session.title(), session.model(), session.cwd(),
                "active", session.createdAt(), session.updatedAt());
    }

    public record CreateSessionRequest(String title, String model, String cwd) {
    }

    public record UpdateSessionRequest(String title, String model) {
    }

    public record SubmitFeedbackRequest(String messageId, Integer rating, String text) {
    }
}