package com.example.dsh.agent;

import com.example.dsh.compaction.CompactionBoundaryStore;
import com.example.dsh.compaction.CompactionProperties;
import com.example.dsh.compaction.CompactionService;
import com.example.dsh.core.model.Agent;
import com.example.dsh.core.model.MessageRole;
import com.example.dsh.core.model.Session;
import com.example.dsh.core.model.SessionId;
import com.example.dsh.core.model.SessionMessage;
import com.example.dsh.core.prompt.SystemPromptService;
import com.example.dsh.llm.LlmGateway;
import com.example.dsh.session.SessionService;
import com.example.dsh.tool.AgentTool;
import com.example.dsh.tool.ToolExecutionPipeline;
import com.example.dsh.tool.ToolEventPublisher;
import com.example.dsh.tool.ToolRegistry;
import com.example.dsh.util.JsonUtils;
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
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 压缩集成测试：历史超阈值时，循环先调用摘要、持久化摘要 USER 消息，再继续正常 turn。
 */
class AgentLoopCompactionTest {

    private final SessionId sessionId = SessionId.of("sess_comp");
    private final Session session = new Session(sessionId, "压缩", null, "/workspace",
            Instant.now(), Instant.now());

    @Test
    void compactsHistoryBeforeToolTurn() {
        // 40 条 × 1000 字符历史 → 明显超阈值
        List<SessionMessage> persisted = new ArrayList<>();
        for (int i = 1; i <= 40; i++) {
            persisted.add(new SessionMessage("msg_" + i, sessionId, MessageRole.USER,
                    "x".repeat(1000), null, null, null, i, Instant.now()));
        }
        // 当前用户消息（turn 开始时追加）→ 最后一条
        SessionMessage currentUser = new SessionMessage("msg_cur", sessionId, MessageRole.USER,
                "继续", null, null, null, 41, Instant.now());
        persisted.add(currentUser);

        // 脚本网关：第 1 次调用 = 摘要（返回 "压缩摘要文本"）；第 2 次 = 工具调用；第 3 次 = 最终文本
        AtomicInteger calls = new AtomicInteger();
        LlmGateway gateway = new LlmGateway() {
            @Override
            public ChatResponse call(List<Message> messages, ChatOptions options) {
                int i = calls.getAndIncrement();
                return switch (i) {
                    case 0 -> new ChatResponse(List.of(new Generation(
                            new AssistantMessage("压缩摘要文本"))));
                    case 1 -> new ChatResponse(List.of(new Generation(AssistantMessage.builder()
                            .toolCalls(List.of(new AssistantMessage.ToolCall("call_1", "function", "echo", "{\"text\":\"hi\"}")))
                            .build())));
                    default -> new ChatResponse(List.of(new Generation(new AssistantMessage("压缩后完成"))));
                };
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

        // 捕获 append 的历史
        List<SessionMessage> appended = new ArrayList<>(persisted);
        SessionService sessionService = mock(SessionService.class);
        when(sessionService.getSession(sessionId)).thenReturn(session);
        when(sessionService.listMessages(sessionId)).thenAnswer(inv -> List.copyOf(appended));
        when(sessionService.append(any(), any(), any(), any(), any(), any())).thenAnswer(inv -> {
            SessionMessage m = new SessionMessage("msg_" + appended.size(), sessionId,
                    inv.getArgument(1), inv.getArgument(2), inv.getArgument(3),
                    inv.getArgument(4), inv.getArgument(5), appended.size() + 1L, Instant.now());
            appended.add(m);
            return m;
        });

        ApplicationContext ctx = mock(ApplicationContext.class);
        AtomicInteger echoExecuted = new AtomicInteger();
        when(ctx.getBeansOfType(AgentTool.class)).thenReturn(Map.of(
                "echoTool", new AgentLoopServiceTest.EchoTool(echoExecuted)));
        ToolRegistry registry = new ToolRegistry(ctx);
        com.example.dsh.core.event.SessionEventBus bus = new com.example.dsh.core.event.SessionEventBus();
        ToolExecutionPipeline pipeline = new ToolExecutionPipeline(registry, List.of(), List.of(),
                new ToolEventPublisher(bus, new JsonUtils()), new JsonUtils());

        CompactionService compaction = new CompactionService(
                new CompactionProperties(true, 1000, 10, 2000));
        CompactionBoundaryStore boundaryStore = new CompactionBoundaryStore(
                new com.example.dsh.storage.StorageService(mockStorageBackends()));
        AgentLoopService loop = new AgentLoopService(gateway, sessionService, registry, pipeline,
                new SystemPromptService(List.of()),
                new AgentProvider("main", "Archon", "deepseek", "", "", "/workspace"),
                null, // 默认作用域（未用注册表）
                null, // 无凭据服务（不解析 agent credentialRef）
                null, // 无转存服务
                new MessageProjector(new JsonUtils()), bus, new JsonUtils(),
                new AgentLoopProperties(10, 0.7, 200, 4), compaction, boundaryStore,
                new com.example.dsh.sandbox.SandboxPolicyService("workspace-write"),
                new ModelRetryPolicy(1, 1, 10), null, null);

        AgentRunResult result = loop.run(AgentRunRequest.builder()
                .sessionId(sessionId)
                .userMessage("继续")
                .build());

        assertEquals("压缩后完成", result.content());
        assertEquals(2, result.steps());
        // 摘要被持久化为 USER 消息（在回放与当前用户消息之间）
        long summaryCount = appended.stream()
                .filter(m -> m.content() != null && m.content().contains("（历史压缩摘要）"))
                .count();
        assertEquals(1, summaryCount, "摘要应作为 USER 消息持久化");
        // 工具执行 + 最终消息
        assertEquals(1, echoExecuted.get());
        assertTrue(calls.get() >= 3, "应发生 摘要 + 工具轮 + 最终轮 三次模型调用");
    }

    /**
     * shadow boundary 测试：turn1 压缩后，turn2 的有效历史低于阈值不再压缩，
     * 回放从遮蔽边界起播 — 不重发被摘要覆盖的旧头（HDR1..HDR31），保留摘要与尾部。
     */
    @Test
    void replaySkipsShadowedHeadOnNextTurn() {
        // 40 条 × 1000 字符 → turn1 超阈值压缩；keepTail=10 → 边界 31；turn2 有效历史 ≈2.3k < 5k 不压缩
        List<SessionMessage> persisted = new ArrayList<>();
        for (int i = 1; i <= 40; i++) {
            persisted.add(new SessionMessage("msg_" + i, sessionId, MessageRole.USER,
                    "HDR" + i + ":" + "x".repeat(1000), null, null, null, i, Instant.now()));
        }

        // 记录每次模型调用收到的消息；第 0 次 = 摘要生成，之后 = 普通回答
        List<List<Message>> prompts = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        LlmGateway gateway = new LlmGateway() {
            @Override
            public ChatResponse call(List<Message> messages, ChatOptions options) {
                prompts.add(messages);
                int i = calls.getAndIncrement();
                return switch (i) {
                    case 0 -> new ChatResponse(List.of(new Generation(new AssistantMessage("压缩摘要文本"))));
                    default -> new ChatResponse(List.of(new Generation(new AssistantMessage("继续完成"))));
                };
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
        when(sessionService.getSession(sessionId)).thenReturn(session);
        when(sessionService.listMessages(sessionId)).thenAnswer(inv -> List.copyOf(appended));
        when(sessionService.append(any(), any(), any(), any(), any(), any())).thenAnswer(inv -> {
            SessionMessage m = new SessionMessage("msg_" + appended.size(), sessionId,
                    inv.getArgument(1), inv.getArgument(2), inv.getArgument(3),
                    inv.getArgument(4), inv.getArgument(5), appended.size() + 1L, Instant.now());
            appended.add(m);
            return m;
        });

        ApplicationContext ctx = mock(ApplicationContext.class);
        when(ctx.getBeansOfType(AgentTool.class)).thenReturn(Map.of());
        ToolRegistry registry = new ToolRegistry(ctx);
        com.example.dsh.core.event.SessionEventBus bus = new com.example.dsh.core.event.SessionEventBus();
        ToolExecutionPipeline pipeline = new ToolExecutionPipeline(registry, List.of(), List.of(),
                new ToolEventPublisher(bus, new JsonUtils()), new JsonUtils());

        CompactionService compaction = new CompactionService(
                new CompactionProperties(true, 5000, 10, 2000));
        CompactionBoundaryStore boundaryStore = new CompactionBoundaryStore(
                new com.example.dsh.storage.StorageService(mockStorageBackends()));
        AgentLoopService loop = new AgentLoopService(gateway, sessionService, registry, pipeline,
                new SystemPromptService(List.of()),
                new AgentProvider("main", "Archon", "deepseek", "", "", "/workspace"),
                null, // 默认作用域（未用注册表）
                null, // 无凭据服务（不解析 agent credentialRef）
                null, // 无转存服务
                new MessageProjector(new JsonUtils()), bus, new JsonUtils(),
                new AgentLoopProperties(10, 0.7, 200, 4), compaction, boundaryStore,
                new com.example.dsh.sandbox.SandboxPolicyService("workspace-write"),
                new ModelRetryPolicy(1, 1, 10), null, null);

        loop.run(AgentRunRequest.builder().sessionId(sessionId).userMessage("继续1").build());
        assertEquals(31, boundaryStore.read(sessionId), "turn1 压缩后边界 = 遮蔽的 31 条旧头");

        loop.run(AgentRunRequest.builder().sessionId(sessionId).userMessage("继续2").build());
        assertEquals(31, boundaryStore.read(sessionId), "turn2 有效历史未超阈值，边界不变");

        // turn2 的模型提示 = 回放：从边界起播 → 不含被遮蔽的 HDR1..HDR31，含摘要与尾部 HDR32
        List<Message> replayPrompt = prompts.get(prompts.size() - 1);
        String text = replayPrompt.stream().map(Message::getText).collect(Collectors.joining("\n"));
        for (int i = 1; i <= 31; i++) {
            assertFalse(text.contains("HDR" + i + ":"), "回放不应包含被遮蔽的消息 HDR" + i);
        }
        assertTrue(text.contains("HDR32:"), "回放应包含尾部第一条 HDR32");
        assertTrue(text.contains("（历史压缩摘要）"), "回放应包含摘要消息");
        assertTrue(text.contains("继续2"), "回放应以当前用户消息结束");
    }

    @SuppressWarnings("unchecked")
    private static org.springframework.beans.factory.ObjectProvider<com.example.dsh.storage.StorageBackend> mockStorageBackends() {
        org.springframework.beans.factory.ObjectProvider<com.example.dsh.storage.StorageBackend> sp =
                mock(org.springframework.beans.factory.ObjectProvider.class);
        when(sp.orderedStream()).thenReturn(
                java.util.stream.Stream.of(new com.example.dsh.storage.InMemoryStorageBackend()));
        return sp;
    }

    /**
     * /compact 手动压缩（对应 DSH command-compact）：低于自动阈值也压缩较老历史段；
     * 无可压缩历史时返回提示且不写摘要/边界。
     */
    @Test
    void manualCompactWorksBelowAutomaticThreshold() {
        // 少量消息（低于自动阈值 5000 tokens）
        List<SessionMessage> persisted = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            persisted.add(new SessionMessage("m" + i, sessionId, MessageRole.USER,
                    "HDR" + i + ":" + "y".repeat(200), null, null, null, i, Instant.now()));
        }
        AtomicInteger calls = new AtomicInteger();
        LlmGateway gateway = new LlmGateway() {
            @Override
            public ChatResponse call(List<Message> messages, ChatOptions options) {
                calls.incrementAndGet();
                return new ChatResponse(List.of(new Generation(new AssistantMessage("手动压缩摘要"))));
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
        when(sessionService.getSession(sessionId)).thenReturn(session);
        when(sessionService.listMessages(sessionId)).thenAnswer(inv -> List.copyOf(appended));
        when(sessionService.append(any(), any(), any(), any(), any(), any())).thenAnswer(inv -> {
            SessionMessage m = new SessionMessage("m" + appended.size(), sessionId,
                    inv.getArgument(1), inv.getArgument(2), inv.getArgument(3),
                    inv.getArgument(4), inv.getArgument(5), appended.size() + 1L, Instant.now());
            appended.add(m);
            return m;
        });

        ApplicationContext ctx = mock(ApplicationContext.class);
        when(ctx.getBeansOfType(AgentTool.class)).thenReturn(Map.of());
        ToolRegistry registry = new ToolRegistry(ctx);
        var bus = new com.example.dsh.core.event.SessionEventBus();
        ToolExecutionPipeline pipeline = new ToolExecutionPipeline(registry, List.of(), List.of(),
                new ToolEventPublisher(bus, new JsonUtils()), new JsonUtils());
        CompactionService compaction = new CompactionService(
                new CompactionProperties(true, 5000, 2, 2000));
        CompactionBoundaryStore boundaryStore = new CompactionBoundaryStore(
                new com.example.dsh.storage.StorageService(mockStorageBackends()));
        AgentLoopService loop = new AgentLoopService(gateway, sessionService, registry, pipeline,
                new SystemPromptService(List.of()),
                new AgentProvider("main", "Archon", "deepseek", "", "", "/workspace"),
                null, null, null,
                new MessageProjector(new JsonUtils()), bus, new JsonUtils(),
                new AgentLoopProperties(10, 0.7, 200, 4), compaction, boundaryStore,
                new com.example.dsh.sandbox.SandboxPolicyService("workspace-write"),
                new ModelRetryPolicy(1, 1, 10), null, null);

        // 自动阈值 5000：4 条小消息不会自动压缩
        assertEquals(false, compaction.needsCompaction(persisted));

        String result = loop.manualCompact(sessionId);
        assertTrue(result.contains("已压缩"), "手动压缩应报告压缩: " + result);
        assertTrue(calls.get() >= 1, "应调用摘要生成");
        // keepTailMessages 构造器有下限 10：4 条消息 → keep=min(10,3)=3，压缩 1 条
        assertEquals(1, boundaryStore.read(sessionId), "边界 = 压缩的 1 条（keepTail 下限 10 夹取）");
        long summaryCount = appended.stream()
                .filter(m -> m.content() != null && m.content().contains("（历史压缩摘要）"))
                .count();
        assertEquals(1, summaryCount, "摘要应持久化为 USER 消息");

        // 第二次（无可压缩历史 → 空会话）不写摘要
        SessionId emptyId = SessionId.of("sess_empty");
        when(sessionService.listMessages(emptyId)).thenReturn(List.of());
        AgentLoopService emptyLoop = new AgentLoopService(gateway, sessionService, registry, pipeline,
                new SystemPromptService(List.of()),
                new AgentProvider("main", "Archon", "deepseek", "", "", "/workspace"),
                null, null, null,
                new MessageProjector(new JsonUtils()), bus, new JsonUtils(),
                new AgentLoopProperties(10, 0.7, 200, 4), compaction, boundaryStore,
                new com.example.dsh.sandbox.SandboxPolicyService("workspace-write"),
                new ModelRetryPolicy(1, 1, 10), null, null);
        assertEquals("No compactable history yet.", emptyLoop.manualCompact(emptyId));
        assertEquals(0, boundaryStore.read(emptyId), "无可压缩历史时不写边界");
    }
}
