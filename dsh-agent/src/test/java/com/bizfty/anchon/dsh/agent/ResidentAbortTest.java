package com.bizfty.anchon.dsh.agent;

import com.bizfty.anchon.dsh.core.event.SessionEventBus;
import com.bizfty.anchon.dsh.core.model.Session;
import com.bizfty.anchon.dsh.core.model.SessionId;
import com.bizfty.anchon.dsh.core.prompt.SystemPromptService;
import com.bizfty.anchon.dsh.llm.LlmGateway;
import com.bizfty.anchon.dsh.session.SessionService;
import com.bizfty.anchon.dsh.tool.AgentTool;
import com.bizfty.anchon.dsh.tool.Tool;
import com.bizfty.anchon.dsh.tool.ToolCall;
import com.bizfty.anchon.dsh.tool.ToolContext;
import com.bizfty.anchon.dsh.tool.ToolExecutionPipeline;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * M4-3 abort 对象态取消（design §4.2，红线 §6-3）：
 * 非流式 step 间隙停止 + 工具不中断；流式 turn 中 abort → 订阅中断（延迟显著低于完整 turn）；
 * ResidentAgent 运行中任务取消 → phase=aborted。
 */
class ResidentAbortTest {

    private final SessionId sessionId = SessionId.of("sess_abort");
    private final Session session = new Session(sessionId, "取消", null, "/workspace",
            Instant.now(), Instant.now());

    /** 脚本 gateway：response1=工具调用（触发 echo 工具轮），response2=纯文本。 */
    private static final class ScriptedGateway implements LlmGateway {
        final List<AssistantMessage> responses;
        final AtomicInteger calls = new AtomicInteger();

        ScriptedGateway(AssistantMessage... responses) {
            this.responses = List.of(responses);
        }

        @Override
        public ChatResponse call(List<Message> messages, ChatOptions options) {
            int i = calls.getAndIncrement();
            if (i >= responses.size()) {
                throw new IllegalStateException("脚本响应用尽");
            }
            return new ChatResponse(List.of(new Generation(responses.get(i))));
        }

        @Override
        public Flux<ChatResponse> stream(List<Message> messages, ChatOptions options) {
            return Flux.just(call(messages, options));
        }

        @Override
        public String defaultModel() {
            return "deepseek-chat";
        }
    }

    @Tool(name = "gate", description = "可阻塞工具：验证 abort 不中断正在执行的工具")
    static class GateTool implements AgentTool {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger executed = new AtomicInteger();

        @Override
        public String name() {
            return "gate";
        }

        @Override
        public ToolSchema getSchema() {
            return ToolSchema.builder().name(name()).description("gate")
                    .addParameter("text", "string", "文本").required("text").build();
        }

        @Override
        public ToolResult execute(ToolCall call, ToolContext context) {
            executed.incrementAndGet();
            entered.countDown();
            try {
                release.await(5, TimeUnit.SECONDS); // 模拟长工具：abort 后仍跑完
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return ToolResult.success("gate: done");
        }
    }

    private AgentLoopService newLoop(LlmGateway gateway, GateTool gateTool, ResidentAgentRegistry registry) {
        SessionService sessionService = mock(SessionService.class);
        when(sessionService.getSession(sessionId)).thenReturn(session);
        when(sessionService.listMessages(sessionId)).thenReturn(List.of());

        ApplicationContext ctx = mock(ApplicationContext.class);
        when(ctx.getBeansOfType(AgentTool.class)).thenReturn(
                gateTool == null ? Map.of() : Map.of("gate", gateTool));
        ToolRegistry toolRegistry = new ToolRegistry(ctx);
        ToolExecutionPipeline pipeline = new ToolExecutionPipeline(toolRegistry, List.of(), List.of(),
                new com.bizfty.anchon.dsh.tool.ToolEventPublisher(new SessionEventBus(), new JsonUtils()),
                new JsonUtils());
        SystemPromptService promptService = new SystemPromptService(List.of());
        AgentProvider agentProvider = new AgentProvider("main", "Archon", "deepseek", "", "", "/workspace");
        AgentLoopProperties props = new AgentLoopProperties(10, 0.7, 200, 4);
        AgentLoopService loop = new AgentLoopService(gateway, sessionService, toolRegistry, pipeline, promptService,
                agentProvider, null, null, null,
                new MessageProjector(new JsonUtils()), new SessionEventBus(), new JsonUtils(), props,
                new com.bizfty.anchon.dsh.compaction.CompactionService(
                        new com.bizfty.anchon.dsh.compaction.CompactionProperties(false, 8000, 40, 2000)),
                null,
                new com.bizfty.anchon.dsh.sandbox.SandboxPolicyService("workspace-write"),
                new ModelRetryPolicy(3, 1, 10), null, null, null, null);
        if (registry != null) {
            loop.setResidentAgentRegistry(registry);
        }
        return loop;
    }

    private static AssistantMessage assistantWithToolCall(String id, String name, String argsJson) {
        return AssistantMessage.builder()
                .toolCalls(List.of(new AssistantMessage.ToolCall(id, "function", name, argsJson)))
                .build();
    }

    @Test
    void nonStreamingAbortStopsAtStepGapAndDoesNotInterruptTool() throws Exception {
        GateTool gate = new GateTool();
        ScriptedGateway gateway = new ScriptedGateway(
                assistantWithToolCall("tc1", "gate", "{\"text\":\"长跑\"}"),
                new AssistantMessage("第二响应（不应到达）"));
        ResidentAgentRegistry registry = new ResidentAgentRegistry();
        AgentLoopService loop = newLoop(gateway, gate, registry); // 委托路径
        ResidentAgent agent = registry.agent(sessionId);

        CompletableFuture<AgentRunResult> runFuture = CompletableFuture.supplyAsync(() -> loop.run(
                AgentRunRequest.builder().sessionId(sessionId).userMessage("跑工具").build()));
        assertTrue(gate.entered.await(3, TimeUnit.SECONDS), "工具应已开始执行");

        // 工具执行中 abort → 不中断工具；工具完成后 step 间隙停止
        loop.abortSession(sessionId);
        gate.release.countDown();

        try {
            runFuture.get(5, TimeUnit.SECONDS);
            fail("应抛 AgentCancelledException（step 间隙取消）");
        } catch (ExecutionException e) {
            assertTrue(e.getCause() instanceof AgentCancelledException,
                    "cause=" + e.getCause());
        }
        assertEquals(1, gate.executed.get(), "abort 不中断正在执行的工具（工具跑完一次）");
        assertEquals(1, gateway.calls.get(), "第二模型调用不应发生（step 间隙已停）");
        assertEquals(ResidentPhase.ABORTED, agent.phase(), "取消后 phase=aborted（停驻可 resume）");
    }

    @Test
    void streamingAbortCancelsSubscriptionPromptly() throws Exception {
        // 可控 chunk 发射流：首 chunk 立即发（触发 onToken），之后按 20ms 间隔持续发，
        // 直到下游取消（doOnNext 取消检查抛异常 → sink cancelled 退出）。完整流无上限 → 不取消永不结束。
        CountDownLatch chunk0Sent = new CountDownLatch(1);
        AtomicInteger chunks = new AtomicInteger();
        LlmGateway streaming = new LlmGateway() {
            @Override
            public ChatResponse call(List<Message> messages, ChatOptions options) {
                throw new IllegalStateException("应走 stream");
            }

            @Override
            public Flux<ChatResponse> stream(List<Message> messages, ChatOptions options) {
                return Flux.create(sink -> {
                    sink.next(new ChatResponse(List.of(new Generation(new AssistantMessage("chunk0")))));
                    chunk0Sent.countDown();
                    int i = 1;
                    while (!sink.isCancelled()) {
                        try {
                            Thread.sleep(20);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                        if (!sink.isCancelled()) {
                            chunks.incrementAndGet();
                            sink.next(new ChatResponse(List.of(new Generation(
                                    new AssistantMessage("chunk" + (i++))))));
                        }
                    }
                    sink.complete();
                }, reactor.core.publisher.FluxSink.OverflowStrategy.BUFFER);
            }

            @Override
            public String defaultModel() {
                return "deepseek-chat";
            }
        };
        AgentLoopService loop = newLoop(streaming, null, new ResidentAgentRegistry());
        AtomicReference<String> lastToken = new AtomicReference<>("");
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            CompletableFuture<AgentRunResult> streamFuture = CompletableFuture.supplyAsync(() ->
                    loop.stream(AgentRunRequest.builder().sessionId(sessionId).userMessage("流式").build(),
                            lastToken::set, null), executor);

            assertTrue(chunk0Sent.await(3, TimeUnit.SECONDS), "首 chunk 已发出（流进行中）");
            assertTrue(lastToken.get().startsWith("chunk0"), "onToken 已透传首 chunk，lastToken=" + lastToken.get());
            long t0 = System.nanoTime();
            loop.abortSession(sessionId);

            try {
                streamFuture.get(2, TimeUnit.SECONDS);
                fail("流式应被取消");
            } catch (ExecutionException e) {
                assertTrue(e.getCause() instanceof AgentCancelledException,
                        "流式中断应抛 AgentCancelledException，cause=" + e.getCause());
            }
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
            // ModelRetryPolicy 白名单（不重试取消）：~1 发射间隔内中断（20ms 级）→ 不取消永不结束的流，
            // 取消后迅速中断即证明订阅 cancel 生效；若取消被重试则会 ModelCallFailed（非 AgentCancelled）。
            assertTrue(elapsedMs < 500, "取消延迟应远低于无上限完整流（elapsedMs=" + elapsedMs + "）");
            assertTrue(chunks.get() < 10, "取消后流应快速停止（额外 chunk 数=" + chunks.get() + "）");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void residentAgentRunningTaskAbortMarksPhaseAborted() throws Exception {
        ResidentAgentRegistry registry = new ResidentAgentRegistry();
        ResidentAgent agent = registry.agent(sessionId);
        CountDownLatch taskStarted = new CountDownLatch(1);
        CountDownLatch taskExited = new CountDownLatch(1);
        CompletableFuture<Void> task = agent.submit("coop", () -> {
            taskStarted.countDown();
            // 协作任务循环：在检查点读对象态取消标志
            while (!agent.isAbortRequested()) {
                Thread.sleep(5);
            }
            taskExited.countDown();
            throw new AgentCancelledException("协作取消");
        });
        assertTrue(taskStarted.await(3, TimeUnit.SECONDS));
        agent.abort();
        try {
            task.get(5, TimeUnit.SECONDS);
            fail("协作任务应抛 AgentCancelledException");
        } catch (ExecutionException e) {
            assertTrue(e.getCause() instanceof AgentCancelledException);
        }
        assertTrue(taskExited.await(3, TimeUnit.SECONDS));
        assertEquals(ResidentPhase.ABORTED, agent.phase());
        registry.release(sessionId);
    }
}
