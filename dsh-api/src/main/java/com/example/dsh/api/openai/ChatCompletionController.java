package com.example.dsh.api.openai;

import com.example.dsh.agent.AgentLoopService;
import com.example.dsh.agent.AgentRunRequest;
import com.example.dsh.agent.AgentRunResult;
import com.example.dsh.core.model.Session;
import com.example.dsh.core.model.SessionId;
import com.example.dsh.session.SessionService;
import com.example.dsh.user.AuthService;
import com.example.dsh.user.UserService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * OpenAI 兼容 Chat Completions 端点（对应 DSH api/gateway 的 OpenAI 兼容面；
 * 任何 OpenAI SDK 可直接接入）。
 */
@RestController
@RequestMapping("/v1")
public class ChatCompletionController {

    private final SessionService sessionService;
    private final AgentLoopService agentLoopService;
    private final AuthService authService;
    private final UserService userService;

    public ChatCompletionController(SessionService sessionService, AgentLoopService agentLoopService,
                                    AuthService authService, UserService userService) {
        this.sessionService = sessionService;
        this.agentLoopService = agentLoopService;
        this.authService = authService;
        this.userService = userService;
    }

    /** 未显式指定 model 时，用已认证用户的 profile 模型兜底。 */
    private String resolveUserModel(String authToken, String requestedModel) {
        if (requestedModel != null && !requestedModel.isBlank()) {
            return requestedModel;
        }
        if (authToken != null) {
            var userId = authService.authenticate(authToken);
            if (userId.isPresent()) {
                String userModel = userService.findById(userId.get())
                        .map(u -> u.llmModel()).orElse(null);
                if (userModel != null && !userModel.isBlank()) {
                    return userModel;
                }
            }
        }
        return requestedModel;
    }

    /** 已认证用户的 LLM API key（profile 经 CredentialService 存储）；未认证/未配置 → null（用全局 key）。 */
    private String resolveUserApiKey(String authToken) {
        if (authToken == null) {
            return null;
        }
        return authService.authenticate(authToken)
                .flatMap(userService::getLlmApiKey)
                .orElse(null);
    }

    @PostMapping(value = "/chat/completions", produces = MediaType.APPLICATION_JSON_VALUE)
    public ChatCompletionResponse chatCompletions(@RequestBody ChatCompletionRequest request,
                                                  HttpServletRequest httpRequest) {
        Session session = resolveSession(request);
        String userMessage = extractUserMessage(request);
        String authToken = httpRequest.getHeader("X-Auth-Token");
        String model = resolveUserModel(authToken, request.model());
        String apiKey = resolveUserApiKey(authToken);
        AgentRunResult result = agentLoopService.run(AgentRunRequest.builder()
                .sessionId(session.id())
                .userMessage(userMessage)
                .modelOverride(model)
                .apiKeyOverride(apiKey)
                .build());
        return new ChatCompletionResponse(
                "chatcmpl-" + UUID.randomUUID(),
                System.currentTimeMillis() / 1000,
                model == null ? session.model() : model,
                List.of(new ChatCompletionResponse.Choice(0,
                        new ChatCompletionResponse.ChatMessage("assistant", result.content()), "stop")),
                new ChatCompletionResponse.Usage(0, 0, 0));
    }

    @PostMapping(value = "/chat/completions", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatCompletionsStream(@RequestBody ChatCompletionRequest request,
                                            HttpServletRequest httpRequest) {
        SseEmitter emitter = new SseEmitter(300_000L);
        Session session = resolveSession(request);
        String userMessage = extractUserMessage(request);
        String authToken = httpRequest.getHeader("X-Auth-Token");
        String model = resolveUserModel(authToken,
                request.model() == null ? session.model() : request.model());
        String apiKey = resolveUserApiKey(authToken);
        Thread.startVirtualThread(() -> {
            try {
                agentLoopService.stream(
                        AgentRunRequest.builder()
                                .sessionId(session.id())
                                .userMessage(userMessage)
                                .modelOverride(model)
                                .apiKeyOverride(apiKey)
                                .build(),
                        token -> sendChunk(emitter, model, new ChatCompletionChunk.ChunkChoice(0,
                                new ChatCompletionChunk.Delta(null, token), null)),
                        toolEvent -> {
                            // OpenAI 兼容流没有工具事件通道；忽略（自有 /chat/stream 提供）
                        });
                sendChunk(emitter, model, new ChatCompletionChunk.ChunkChoice(0,
                        new ChatCompletionChunk.Delta(null, null), "stop"));
                emitter.complete();
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }

    private void sendChunk(SseEmitter emitter, String model, ChatCompletionChunk.ChunkChoice choice) {
        try {
            ChatCompletionChunk chunk = new ChatCompletionChunk(
                    "chatcmpl-" + UUID.randomUUID(), System.currentTimeMillis() / 1000, model, List.of(choice));
            emitter.send(SseEmitter.event().name("").data(chunk, MediaType.APPLICATION_JSON));
        } catch (Exception e) {
            throw new RuntimeException("SSE 发送失败", e);
        }
    }

    private Session resolveSession(ChatCompletionRequest request) {
        String sessionId = request.metadata() == null ? null : (String) request.metadata().get("session_id");
        if (sessionId != null && !sessionId.isBlank()) {
            return sessionService.getSession(SessionId.of(sessionId));
        }
        return sessionService.createSession(null, request.model(), null);
    }

    private String extractUserMessage(ChatCompletionRequest request) {
        if (request.messages() == null || request.messages().isEmpty()) {
            return "";
        }
        for (int i = request.messages().size() - 1; i >= 0; i--) {
            ChatCompletionRequest.ChatMessage m = request.messages().get(i);
            if ("user".equalsIgnoreCase(m.role()) && m.content() != null) {
                return m.content().toString();
            }
        }
        ChatCompletionRequest.ChatMessage last = request.messages().get(request.messages().size() - 1);
        return last.content() == null ? "" : last.content().toString();
    }
}
