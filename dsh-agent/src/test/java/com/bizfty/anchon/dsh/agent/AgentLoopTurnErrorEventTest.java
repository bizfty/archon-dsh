package com.bizfty.anchon.dsh.agent;

import com.bizfty.anchon.dsh.compaction.CompactionProperties;
import com.bizfty.anchon.dsh.compaction.CompactionService;
import com.bizfty.anchon.dsh.core.event.SessionEvent;
import com.bizfty.anchon.dsh.core.event.SessionEventBus;
import com.bizfty.anchon.dsh.core.event.SessionEventType;
import com.bizfty.anchon.dsh.core.model.MessageRole;
import com.bizfty.anchon.dsh.core.model.Session;
import com.bizfty.anchon.dsh.core.model.SessionId;
import com.bizfty.anchon.dsh.core.model.SessionMessage;
import com.bizfty.anchon.dsh.core.prompt.SystemPromptService;
import com.bizfty.anchon.dsh.llm.LlmGateway;
import com.bizfty.anchon.dsh.session.SessionService;
import com.bizfty.anchon.dsh.tool.AgentTool;
import com.bizfty.anchon.dsh.tool.ToolCall;
import com.bizfty.anchon.dsh.tool.ToolContext;
import com.bizfty.anchon.dsh.tool.ToolExecutionPipeline;
import com.bizfty.anchon.dsh.tool.ToolEventPublisher;
import com.bizfty.anchon.dsh.tool.ToolRegistry;
import com.bizfty.anchon.dsh.tool.ToolResult;
import com.bizfty.anchon.dsh.tool.ToolSchema;
import com.bizfty.anchon.dsh.util.JsonUtils;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.context.ApplicationContext;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * TURN_ERROR 事件发布测试：agent loop 失败（如超步数上限）时发布 TURN_ERROR 事件。
 */
class AgentLoopTurnErrorEventTest {

    private final SessionId sessionId = SessionId.of("sess_err");
    private final Session session = new Session(sessionId, "err", null, "/workspace",
            Instant.now(), Instant.now());

    /** 始终返回带工具调用的响应 → 永不满足"无工具调用即终态"，逼出超步数异常。 */
    private LlmGateway loopForeverGateway() {
        return new LlmGateway() {
            @Override
            public ChatResponse call(List<Message> messages, ChatOptions options) {
                return new ChatResponse(List.of(new Generation(AssistantMessage.builder()
                        .toolCalls(List.of(new AssistantMessage.ToolCall("c1", "function", "no_such_tool", "{}")))
                        .build())));
            }

            @Override
            public Flux<ChatResponse> stream(List<Message> messages, ChatOptions options) {
                return Flux.just(call(messages, options));
            }

            @Override
            public String defaultModel() {
                return "deepseek-chat";
            }
        };
    }

    private AgentLoopService loop(LlmGateway gateway, int maxSteps, SessionEventBus bus) {
        SessionService sessionService = mock(SessionService.class);
        when(sessionService.getSession(sessionId)).thenReturn(session);
        when(sessionService.listMessages(sessionId)).thenReturn(List.of());
        when(sessionService.append(any(), any(), any(), any(), any(), any())).thenAnswer(inv ->
                new SessionMessage("msg_" + System.nanoTime(), sessionId,
                        inv.getArgument(1), inv.getArgument(2), inv.getArgument(3),
                        inv.getArgument(4), inv.getArgument(5), 1L, Instant.now()));
        ApplicationContext ctx = mock(ApplicationContext.class);
        when(ctx.getBeansOfType(AgentTool.class)).thenReturn(Map.of());
        ToolRegistry registry = new ToolRegistry(ctx);
        ToolExecutionPipeline pipeline = new ToolExecutionPipeline(registry, List.of(), List.of(),
                new ToolEventPublisher(bus, new JsonUtils()), new JsonUtils());
        CompactionService compaction = new CompactionService(new CompactionProperties(false, 8000, 40, 2000));
        return new AgentLoopService(gateway, sessionService, registry, pipeline,
                new SystemPromptService(List.of()),
                new AgentProvider("main", "Archon", "deepseek", "", "", "/workspace"),
                null, null, null,
                new MessageProjector(new JsonUtils()), bus, new JsonUtils(),
                new AgentLoopProperties(maxSteps, 0.7, 200, 4), compaction,
                null,
                new com.bizfty.anchon.dsh.sandbox.SandboxPolicyService("workspace-write"),
                new ModelRetryPolicy(1, 1, 10), null, null, null, null);
    }

    @Test
    void turnErrorEventPublishedOnMaxStepsExceeded() {
        // 未知工具会被拒绝但循环不结束 → 2 步即超限
        SessionEventBus bus = new SessionEventBus();
        AgentLoopService loop = loop(loopForeverGateway(), 2, bus);

        List<SessionEvent> turnErrors = new ArrayList<>();
        Runnable disposer = bus.addListener(event -> {
            if (event.type() == SessionEventType.TURN_ERROR) {
                turnErrors.add(event);
            }
        });

        assertThrows(AgentLoopException.class, () -> loop.run(AgentRunRequest.builder()
                .sessionId(sessionId).userMessage("go").build()));

        assertEquals(1, turnErrors.size(), "超步数应发布一次 TURN_ERROR");
        assertTrue(turnErrors.get(0).string("message").contains("超过最大步数上限"),
                "TURN_ERROR 应携带可读 message: " + turnErrors.get(0).string("message"));
        disposer.run();
    }
}
