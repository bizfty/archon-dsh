package com.example.dsh.agent;

import com.example.dsh.core.event.SessionEvent;
import com.example.dsh.core.event.SessionEventBus;
import com.example.dsh.core.event.SessionEventType;
import com.example.dsh.core.model.Agent;
import com.example.dsh.core.model.MessageRole;
import com.example.dsh.core.model.Session;
import com.example.dsh.core.model.SessionId;
import com.example.dsh.core.model.SessionMessage;
import com.example.dsh.core.prompt.SystemPromptContext;
import com.example.dsh.core.prompt.SystemPromptService;
import com.example.dsh.llm.LlmGateway;
import com.example.dsh.session.SessionService;
import com.example.dsh.tool.AgentTool;
import com.example.dsh.tool.Tool;
import com.example.dsh.tool.ToolCall;
import com.example.dsh.tool.ToolContext;
import com.example.dsh.tool.ToolEvent;
import com.example.dsh.tool.ToolExecutionPipeline;
import com.example.dsh.tool.ToolEventPublisher;
import com.example.dsh.tool.ToolRegistry;
import com.example.dsh.tool.ToolResult;
import com.example.dsh.tool.ToolSchema;
import com.example.dsh.util.JsonUtils;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Agent 循环核心测试 — 用脚本化 LlmGateway 验证手动 model→tool→model 循环：
 * 工具被执行一次、消息落库、事件发出、最终文本返回。
 */
class AgentLoopServiceTest {

    private final SessionId sessionId = SessionId.of("sess_test");
    private final Session session = new Session(sessionId, "测试", null, "/workspace",
            Instant.now(), Instant.now());

    private AgentLoopService newLoop(ScriptedGateway gateway, List<SessionMessage> persisted,
                                     AtomicInteger echoExecuted) {
        SessionService sessionService = mock(SessionService.class);
        when(sessionService.getSession(sessionId)).thenReturn(session);
        when(sessionService.listMessages(sessionId)).thenReturn(persisted);

        ApplicationContext ctx = mock(ApplicationContext.class);
        when(ctx.getBeansOfType(AgentTool.class)).thenReturn(Map.of("echoTool", new EchoTool(echoExecuted)));
        ToolRegistry registry = new ToolRegistry(ctx);

        SessionEventBus bus = new SessionEventBus();
        ToolExecutionPipeline pipeline = new ToolExecutionPipeline(registry, List.of(), List.of(),
                new ToolEventPublisher(bus, new JsonUtils()), new JsonUtils());
        SystemPromptService promptService = new SystemPromptService(List.of());
        AgentProvider agentProvider = new AgentProvider("main", "Archon", "deepseek", "", "", "/workspace");
        AgentLoopProperties props = new AgentLoopProperties(10, 0.7, 200, 4);

        return new AgentLoopService(gateway, sessionService, registry, pipeline, promptService,
                agentProvider, null, // 默认作用域（未用注册表）
                null, // 无凭据服务（不解析 agent credentialRef）
                null, // 无转存服务
                new MessageProjector(new JsonUtils()), bus, new JsonUtils(), props,
                disabledCompaction(),
                null, // 无压缩边界存储（本组测试不涉及压缩）
                new com.example.dsh.sandbox.SandboxPolicyService("workspace-write"),
                new ModelRetryPolicy(1, 1, 10), null, null);
    }

    @Test
    void runsToolCallThenFinalAnswer() {
        // 第一次调用返回工具调用 echo(text=hi)，第二次返回最终文本
        ScriptedGateway gateway = new ScriptedGateway(
                assistantWithToolCall("call_1", "echo", "{\"text\":\"hi\"}"),
                assistantText("final answer"));
        AtomicInteger echoExecuted = new AtomicInteger();
        AgentLoopService loop = newLoop(gateway, List.of(), echoExecuted);

        AgentRunResult result = loop.run(AgentRunRequest.builder()
                .sessionId(sessionId)
                .userMessage("请回显 hi")
                .build());

        assertEquals("final answer", result.content());
        assertEquals(2, result.steps());
        assertEquals(1, result.toolCalls());
        assertEquals(1, echoExecuted.get(), "echo 工具应被执行一次");
        assertEquals(2, gateway.calls.get()); // 两次模型调用
    }

    @Test
    void persistsMessagesForEveryModelSurface() {
        ScriptedGateway gateway = new ScriptedGateway(
                assistantWithToolCall("call_1", "echo", "{\"text\":\"x\"}"),
                assistantText("done"));
        List<SessionMessage> persisted = new ArrayList<>();
        SessionService sessionService = mock(SessionService.class);
        when(sessionService.getSession(sessionId)).thenReturn(session);
        when(sessionService.listMessages(sessionId)).thenReturn(List.copyOf(persisted));
        when(sessionService.append(any(), any(), any(), any(), any(), any())).thenAnswer(inv -> {
            SessionMessage m = new SessionMessage("msg_" + persisted.size(), sessionId,
                    inv.getArgument(1), inv.getArgument(2), inv.getArgument(3),
                    inv.getArgument(4), inv.getArgument(5), persisted.size() + 1L, Instant.now());
            persisted.add(m);
            return m;
        });

        ApplicationContext ctx = mock(ApplicationContext.class);
        when(ctx.getBeansOfType(AgentTool.class))
                .thenReturn(Map.of("echoTool", new EchoTool(new AtomicInteger())));
        ToolRegistry registry = new ToolRegistry(ctx);
        SessionEventBus bus = new SessionEventBus();
        ToolExecutionPipeline pipeline = new ToolExecutionPipeline(registry, List.of(), List.of(),
                new ToolEventPublisher(bus, new JsonUtils()), new JsonUtils());
        AgentLoopService loop = new AgentLoopService(gateway, sessionService, registry, pipeline,
                new SystemPromptService(List.of()),
                new AgentProvider("main", "Archon", "deepseek", "", "", "/workspace"),
                null, // 默认作用域（未用注册表）
                null, // 无凭据服务（不解析 agent credentialRef）
                null, // 无转存服务
                new MessageProjector(new JsonUtils()), bus, new JsonUtils(),
                new AgentLoopProperties(10, 0.7, 200, 4), disabledCompaction(),
                null, // 无压缩边界存储（本组测试不涉及压缩）
                new com.example.dsh.sandbox.SandboxPolicyService("workspace-write"),
                new ModelRetryPolicy(1, 1, 10), null, null);

        loop.run(AgentRunRequest.builder().sessionId(sessionId).userMessage("hi").build());

        // USER + ASSISTANT(toolcall) + TOOL + ASSISTANT(text) = 4 条
        assertEquals(4, persisted.size());
        assertEquals(MessageRole.USER, persisted.get(0).role());
        assertEquals(MessageRole.ASSISTANT, persisted.get(1).role());
        assertTrue(persisted.get(1).hasToolCalls());
        assertEquals(MessageRole.TOOL, persisted.get(2).role());
        assertEquals("echo", persisted.get(2).toolName());
        assertEquals(MessageRole.ASSISTANT, persisted.get(3).role());
        assertEquals("done", persisted.get(3).content());
    }

    @Test
    void emitsTurnAndStepEvents() {
        ScriptedGateway gateway = new ScriptedGateway(
                assistantWithToolCall("call_1", "echo", "{\"text\":\"hi\"}"),
                assistantText("ok"));
        SessionEventBus bus = new SessionEventBus();
        List<SessionEvent> events = new ArrayList<>();
        bus.addListener(events::add);

        ApplicationContext ctx = mock(ApplicationContext.class);
        when(ctx.getBeansOfType(AgentTool.class))
                .thenReturn(Map.of("echoTool", new EchoTool(new AtomicInteger())));
        ToolRegistry registry = new ToolRegistry(ctx);
        ToolExecutionPipeline pipeline = new ToolExecutionPipeline(registry, List.of(), List.of(),
                new ToolEventPublisher(bus, new JsonUtils()), new JsonUtils());
        SessionService sessionService = mock(SessionService.class);
        when(sessionService.getSession(sessionId)).thenReturn(session);
        when(sessionService.listMessages(sessionId)).thenReturn(List.of());
        AgentLoopService loop = new AgentLoopService(gateway, sessionService, registry, pipeline,
                new SystemPromptService(List.of()),
                new AgentProvider("main", "Archon", "deepseek", "", "", "/workspace"),
                null, // 默认作用域（未用注册表）
                null, // 无凭据服务（不解析 agent credentialRef）
                null, // 无转存服务
                new MessageProjector(new JsonUtils()), bus, new JsonUtils(),
                new AgentLoopProperties(10, 0.7, 200, 4), disabledCompaction(),
                null, // 无压缩边界存储（本组测试不涉及压缩）
                new com.example.dsh.sandbox.SandboxPolicyService("workspace-write"),
                new ModelRetryPolicy(1, 1, 10), null, null);

        loop.run(AgentRunRequest.builder().sessionId(sessionId).userMessage("hi").build());

        List<SessionEventType> types = events.stream().map(SessionEvent::type).toList();
        assertTrue(types.contains(SessionEventType.TURN_START));
        assertTrue(types.contains(SessionEventType.STEP_START));
        assertTrue(types.contains(SessionEventType.TOOL_CALL));
        assertTrue(types.contains(SessionEventType.TOOL_RESULT));
        assertTrue(types.contains(SessionEventType.TURN_END));
    }

    @Test
    void streamingForwardsTokensAndToolEvents() {
        ScriptedGateway gateway = new ScriptedGateway(
                assistantWithToolCall("call_1", "echo", "{\"text\":\"hi\"}"),
                assistantText("streamed answer"));
        AgentLoopService loop = newLoop(gateway, List.of(), new AtomicInteger());

        StringBuilder tokens = new StringBuilder();
        List<ToolEvent> toolEvents = new ArrayList<>();
        AgentRunResult result = loop.stream(
                AgentRunRequest.builder().sessionId(sessionId).userMessage("hi").build(),
                tokens::append, toolEvents::add);

        assertEquals("streamed answer", result.content());
        assertEquals("streamed answer", tokens.toString());
        assertEquals(1, toolEvents.size());
        assertEquals("echo", toolEvents.get(0).toolName());
        assertTrue(toolEvents.get(0).success());
    }

    private static com.example.dsh.compaction.CompactionService disabledCompaction() {
        return new com.example.dsh.compaction.CompactionService(
                new com.example.dsh.compaction.CompactionProperties(false, 8000, 40, 2000));
    }

    // ===== 测试桩 =====

    /** 脚本化网关：按顺序返回预置响应。 */
    private static final class ScriptedGateway implements LlmGateway {
        private final List<AssistantMessage> responses = new ArrayList<>();
        private final AtomicInteger calls = new AtomicInteger();

        ScriptedGateway(AssistantMessage... responses) {
            this.responses.addAll(List.of(responses));
        }

        @Override
        public ChatResponse call(List<Message> messages, ChatOptions options) {
            return nextResponse();
        }

        @Override
        public Flux<ChatResponse> stream(List<Message> messages, ChatOptions options) {
            return Flux.just(nextResponse());
        }

        private ChatResponse nextResponse() {
            int i = calls.getAndIncrement();
            if (i >= responses.size()) {
                throw new IllegalStateException("脚本响应用尽");
            }
            return new ChatResponse(List.of(new Generation(responses.get(i))));
        }

        @Override
        public String defaultModel() {
            return "deepseek-chat";
        }
    }

    private static AssistantMessage assistantWithToolCall(String id, String name, String argsJson) {
        return AssistantMessage.builder()
                .toolCalls(List.of(new AssistantMessage.ToolCall(id, "function", name, argsJson)))
                .build();
    }

    private static AssistantMessage assistantText(String text) {
        return new AssistantMessage(text);
    }

    @Tool(name = "echo", description = "回显")
    public static class EchoTool implements AgentTool {
        private final java.util.concurrent.atomic.AtomicInteger executed;

        EchoTool(java.util.concurrent.atomic.AtomicInteger executed) {
            this.executed = executed;
        }

        @Override
        public String name() {
            return "echo";
        }

        @Override
        public ToolSchema getSchema() {
            return ToolSchema.builder()
                    .name(name())
                    .description("回显")
                    .addParameter("text", "string", "文本")
                    .required("text")
                    .build();
        }

        @Override
        public ToolResult execute(ToolCall call, ToolContext context) {
            executed.incrementAndGet();
            return ToolResult.success("echo: " + call.getString("text"));
        }
    }

    /**
     * 配对过滤回归：滑动窗口（maxHistoryMessages=2）把 assistant(tool_calls=[B]) 与其 TOOL(B)
     * 切开时，重放不得发出孤立的 TOOL / 裸 tool_calls assistant（OpenAI 400
     * "insufficient tool messages following tool_calls"）。
     */
    @Test
    void replaySkipsOrphanToolWhenWindowCutsPair() {
        SessionId sid = SessionId.of("sess_orphan");
        Session sess = new Session(sid, "孤儿", null, "/workspace", Instant.now(), Instant.now());
        String tcA = new JsonUtils().toJson(List.of(Map.of(
                "id", "A", "type", "function", "name", "echo", "arguments", "{}")));
        String tcB = new JsonUtils().toJson(List.of(Map.of(
                "id", "B", "type", "function", "name", "echo", "arguments", "{}")));
        List<SessionMessage> persisted = new ArrayList<>();
        persisted.add(new SessionMessage("u1", sid, MessageRole.USER, "旧问题", null, null, null, 1, Instant.now()));
        persisted.add(new SessionMessage("a1", sid, MessageRole.ASSISTANT, "", null, null, tcA, 2, Instant.now()));
        persisted.add(new SessionMessage("t1", sid, MessageRole.TOOL, "{\"ok\":true}", "A", "echo", null, 3, Instant.now()));
        persisted.add(new SessionMessage("u2", sid, MessageRole.USER, "第二问题", null, null, null, 4, Instant.now()));
        persisted.add(new SessionMessage("a2", sid, MessageRole.ASSISTANT, "", null, null, tcB, 5, Instant.now()));
        persisted.add(new SessionMessage("t2", sid, MessageRole.TOOL, "{\"ok\":true}", "B", "echo", null, 6, Instant.now()));

        List<Message> captured = new ArrayList<>();
        LlmGateway gateway = new LlmGateway() {
            @Override
            public ChatResponse call(List<Message> messages, ChatOptions options) {
                captured.addAll(messages);
                return new ChatResponse(List.of(new Generation(new AssistantMessage("done"))));
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

        List<SessionMessage> appended = new ArrayList<>(persisted);
        SessionService sessionService = mock(SessionService.class);
        when(sessionService.getSession(sid)).thenReturn(sess);
        when(sessionService.listMessages(sid)).thenAnswer(inv -> List.copyOf(appended));
        when(sessionService.append(any(), any(), any(), any(), any(), any())).thenAnswer(inv -> {
            SessionMessage m = new SessionMessage("m" + appended.size(), sid,
                    inv.getArgument(1), inv.getArgument(2), inv.getArgument(3),
                    inv.getArgument(4), inv.getArgument(5), appended.size() + 1L, Instant.now());
            appended.add(m);
            return m;
        });

        ApplicationContext ctx = mock(ApplicationContext.class);
        when(ctx.getBeansOfType(AgentTool.class)).thenReturn(Map.of());
        ToolRegistry registry = new ToolRegistry(ctx);
        SessionEventBus bus = new SessionEventBus();
        ToolExecutionPipeline pipeline = new ToolExecutionPipeline(registry, List.of(), List.of(),
                new ToolEventPublisher(bus, new JsonUtils()), new JsonUtils());
        // maxHistoryMessages=2：listMessages 返回 7 条（6 历史 + 当前用户），from = 7-2 = 5 → 窗口 [5,6) = TOOL(B)
        AgentLoopService loop = new AgentLoopService(gateway, sessionService, registry, pipeline,
                new SystemPromptService(List.of()),
                new AgentProvider("main", "Archon", "deepseek", "", "", "/workspace"),
                null, null, null, // 默认作用域、无凭据服务、无转存服务
                new MessageProjector(new JsonUtils()), bus, new JsonUtils(),
                new AgentLoopProperties(10, 0.7, 2, 4),
                disabledCompaction(),
                null,
                new com.example.dsh.sandbox.SandboxPolicyService("workspace-write"),
                new ModelRetryPolicy(1, 1, 10), null, null);

        loop.run(AgentRunRequest.builder().sessionId(sid).userMessage("当前问题").build());

        boolean hasOrphanTool = captured.stream().anyMatch(m -> m instanceof ToolResponseMessage);
        boolean hasBareToolCallAssistant = captured.stream()
                .anyMatch(m -> m instanceof AssistantMessage am && am.hasToolCalls());
        assertFalse(hasOrphanTool, "窗口切开时不得发出孤立 TOOL 消息: " + captured);
        assertFalse(hasBareToolCallAssistant, "窗口切开时不得发出无 TOOL 响应的 tool_calls assistant: " + captured);
    }
}
