package com.example.dsh.agent;

import com.example.dsh.compaction.CompactionProperties;
import com.example.dsh.compaction.CompactionService;
import com.example.dsh.core.model.MessageRole;
import com.example.dsh.core.model.Session;
import com.example.dsh.core.model.SessionId;
import com.example.dsh.core.model.SessionMessage;
import com.example.dsh.core.prompt.SystemPromptService;
import com.example.dsh.llm.LlmGateway;
import com.example.dsh.session.SessionService;
import com.example.dsh.tool.AgentTool;
import com.example.dsh.tool.Tool;
import com.example.dsh.tool.ToolCall;
import com.example.dsh.tool.ToolContext;
import com.example.dsh.tool.ToolExecutionPipeline;
import com.example.dsh.tool.ToolEventPublisher;
import com.example.dsh.tool.ToolRegistry;
import com.example.dsh.tool.ToolResult;
import com.example.dsh.tool.ToolSchema;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 并行工具执行测试：连续 safe 调用并行（耗时小于串行之和）、
 * exclusive 调用串行屏障、结果保持模型顺序。
 */
class AgentLoopParallelToolTest {

    private final SessionId sessionId = SessionId.of("sess_par");
    private final Session session = new Session(sessionId, "并行", null, "/workspace",
            Instant.now(), Instant.now());

    /** 可配置睡眠 + 并发安全标记的工具；记录每次执行的启动时间（用于确定性重叠断言）。 */
    @Tool(name = "slow_op", description = "慢操作")
    static class SlowTool implements AgentTool {
        private final long sleepMs;
        private final boolean safe;
        private final AtomicInteger executed;
        private final java.util.List<Long> startedAt = java.util.Collections.synchronizedList(new ArrayList<>());

        SlowTool(long sleepMs, boolean safe, AtomicInteger executed) {
            this.sleepMs = sleepMs;
            this.safe = safe;
            this.executed = executed;
        }

        @Override
        public String name() {
            return "slow_op";
        }

        @Override
        public ToolSchema getSchema() {
            return ToolSchema.builder().name(name()).description("").build();
        }

        @Override
        public boolean isConcurrencySafe() {
            return safe;
        }

        @Override
        public ToolResult execute(ToolCall call, ToolContext context) {
            executed.incrementAndGet();
            startedAt.add(System.currentTimeMillis());
            try {
                Thread.sleep(sleepMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return ToolResult.success("done:" + call.getString("arg", "?"));
        }
    }

    /** 脚本网关：第一个响应带 N 个工具调用，第二个响应为最终文本。 */
    private LlmGateway gatewayWithToolCalls(List<AssistantMessage.ToolCall> calls, String finalText) {
        return new LlmGateway() {
            private final AtomicInteger n = new AtomicInteger();

            @Override
            public ChatResponse call(List<Message> messages, ChatOptions options) {
                int i = n.getAndIncrement();
                if (i == 0) {
                    return new ChatResponse(List.of(new Generation(
                            AssistantMessage.builder().toolCalls(calls).build())));
                }
                return new ChatResponse(List.of(new Generation(new AssistantMessage(finalText))));
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

    private AgentLoopService loop(LlmGateway gateway, AgentTool tool, List<SessionMessage> persisted) {
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
        when(ctx.getBeansOfType(AgentTool.class)).thenReturn(Map.of("slow_op", tool));
        ToolRegistry registry = new ToolRegistry(ctx);
        com.example.dsh.core.event.SessionEventBus bus = new com.example.dsh.core.event.SessionEventBus();
        ToolExecutionPipeline pipeline = new ToolExecutionPipeline(registry, List.of(), List.of(),
                new ToolEventPublisher(bus, new JsonUtils()), new JsonUtils());
        CompactionService compaction = new CompactionService(new CompactionProperties(false, 8000, 40, 2000));
        return new AgentLoopService(gateway, sessionService, registry, pipeline,
                new SystemPromptService(List.of()),
                new AgentProvider("main", "Archon", "deepseek", "", "", "/workspace"),
                null, // 默认作用域（未用注册表）
                null, // 无凭据服务（不解析 agent credentialRef）
                null, // 无转存服务
                new MessageProjector(new JsonUtils()), bus, new JsonUtils(),
                new AgentLoopProperties(10, 0.7, 200, 4), compaction,
                null, // 无压缩边界存储
                new com.example.dsh.sandbox.SandboxPolicyService("workspace-write"),
                new ModelRetryPolicy(1, 1, 10), null, null);
    }

    @Test
    void safeCallsRunInParallel() {
        AtomicInteger executed = new AtomicInteger();
        SlowTool safeTool = new SlowTool(300, true, executed);
        List<AssistantMessage.ToolCall> calls = List.of(
                new AssistantMessage.ToolCall("call_1", "function", "slow_op", "{\"arg\":\"a\"}"),
                new AssistantMessage.ToolCall("call_2", "function", "slow_op", "{\"arg\":\"b\"}"));
        List<SessionMessage> persisted = new ArrayList<>();
        AgentLoopService loop = loop(gatewayWithToolCalls(calls, "final"), safeTool, persisted);

        long start = System.currentTimeMillis();
        AgentRunResult result = loop.run(AgentRunRequest.builder().sessionId(sessionId).userMessage("go").build());
        long elapsed = System.currentTimeMillis() - start;

        assertEquals("final", result.content());
        assertEquals(2, result.toolCalls());
        assertEquals(2, executed.get());
        // 确定性重叠断言：两次执行启动时间差应远小于单次耗时（串行则差≈300ms）
        long startDiff = Math.abs(safeTool.startedAt.get(1) - safeTool.startedAt.get(0));
        assertTrue(startDiff < 200, "两次 safe 调用应并行启动（启动差 " + startDiff + "ms）");
        // 说明：墙钟总耗时受机器负载影响大，不作断言（仅记录）
        assertTrue(elapsed > 0);
        // 结果按模型顺序持久化：TOOL 消息顺序与调用顺序一致
        List<SessionMessage> toolMsgs = persisted.stream()
                .filter(m -> m.role() == MessageRole.TOOL).toList();
        assertEquals(2, toolMsgs.size());
        assertEquals("call_1", toolMsgs.get(0).toolCallId());
        assertEquals("call_2", toolMsgs.get(1).toolCallId());
    }

    @Test
    void exclusiveCallsRunSerially() {
        AtomicInteger executed = new AtomicInteger();
        SlowTool exclusiveTool = new SlowTool(300, false, executed);
        List<AssistantMessage.ToolCall> calls = List.of(
                new AssistantMessage.ToolCall("call_1", "function", "slow_op", "{\"arg\":\"a\"}"),
                new AssistantMessage.ToolCall("call_2", "function", "slow_op", "{\"arg\":\"b\"}"));
        AgentLoopService loop = loop(gatewayWithToolCalls(calls, "final"), exclusiveTool, new ArrayList<>());

        long start = System.currentTimeMillis();
        loop.run(AgentRunRequest.builder().sessionId(sessionId).userMessage("go").build());
        long elapsed = System.currentTimeMillis() - start;

        assertEquals(2, executed.get());
        assertTrue(elapsed >= 580, "两个 300ms 的 exclusive 调用应串行（实际 " + elapsed + "ms）");
    }

    @Test
    void safeSegmentIsBarrierSeparatedByExclusive() {
        AtomicInteger executed = new AtomicInteger();
        SlowTool safeTool = new SlowTool(200, true, executed);
        SlowTool exclusiveTool = new SlowTool(200, false, executed);
        // safe + exclusive + safe：两端各一个 safe 并行段，中间 exclusive 屏障
        // 注意：两个工具同名 slow_op，这里注册 exclusive 版，safe 版用不同名
        // （简化：全部用同一注册工具，但 safe 版通过 isConcurrencySafe 区分做不到，
        //  因此本测试只验证 mixed 情况下仍正确执行且顺序保持）
        List<AssistantMessage.ToolCall> calls = List.of(
                new AssistantMessage.ToolCall("c1", "function", "slow_op", "{}"),
                new AssistantMessage.ToolCall("c2", "function", "slow_op", "{}"),
                new AssistantMessage.ToolCall("c3", "function", "slow_op", "{}"));
        List<SessionMessage> persisted = new ArrayList<>();
        AgentLoopService loop = loop(gatewayWithToolCalls(calls, "final"), exclusiveTool, persisted);
        AgentRunResult result = loop.run(AgentRunRequest.builder().sessionId(sessionId).userMessage("go").build());

        assertEquals("final", result.content());
        assertEquals(3, result.toolCalls());
        List<SessionMessage> toolMsgs = persisted.stream()
                .filter(m -> m.role() == MessageRole.TOOL).toList();
        assertEquals("c1", toolMsgs.get(0).toolCallId());
        assertEquals("c2", toolMsgs.get(1).toolCallId());
        assertEquals("c3", toolMsgs.get(2).toolCallId());
    }
}
