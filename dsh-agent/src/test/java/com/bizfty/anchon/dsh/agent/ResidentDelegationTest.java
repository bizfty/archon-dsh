package com.bizfty.anchon.dsh.agent;

import com.bizfty.anchon.dsh.core.event.SessionEventBus;
import com.bizfty.anchon.dsh.core.model.Session;
import com.bizfty.anchon.dsh.core.model.SessionId;
import com.bizfty.anchon.dsh.core.prompt.SystemPromptService;
import com.bizfty.anchon.dsh.llm.LlmGateway;
import com.bizfty.anchon.dsh.session.SessionService;
import com.bizfty.anchon.dsh.tool.ToolExecutionPipeline;
import com.bizfty.anchon.dsh.tool.ToolRegistry;
import com.bizfty.anchon.dsh.util.JsonUtils;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * M4-2 门面委托验证（design §4.5 / D8）：装配 ResidentAgentRegistry 后 run/stream 委托到
 * per-session 执行者（同步 join 语义不变 + 同会话并发串行）；开关 false 回落直接执行（旧路径）。
 */
class ResidentDelegationTest {

    private final SessionId sessionId = SessionId.of("sess_delegation");
    private final Session session = new Session(sessionId, "委托", null, "/workspace",
            Instant.now(), Instant.now());

    /** 最小 gateway：可选阻塞 + 记录执行线程/开始时间；返回纯文本（无工具调用 → turn 一次结束）。 */
    private static final class RecordingGateway implements LlmGateway {
        final AtomicInteger calls = new AtomicInteger();
        final AtomicReference<String> lastCallerThread = new AtomicReference<>();
        final List<Long> callStartNanos = new ArrayList<>();
        volatile long blockMs = 0;
        final CountDownLatch firstCallEntered = new CountDownLatch(1);

        @Override
        public ChatResponse call(List<Message> messages, ChatOptions options) {
            calls.incrementAndGet();
            lastCallerThread.set(Thread.currentThread().toString());
            callStartNanos.add(System.nanoTime());
            firstCallEntered.countDown();
            if (blockMs > 0) {
                try {
                    Thread.sleep(blockMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return new ChatResponse(List.of(new Generation(new AssistantMessage("你好"))));
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

    private AgentLoopService newDelegatedLoop(RecordingGateway gateway) {
        SessionService sessionService = mock(SessionService.class);
        when(sessionService.getSession(sessionId)).thenReturn(session);
        when(sessionService.listMessages(sessionId)).thenReturn(List.of());

        ToolRegistry toolRegistry = new ToolRegistry(mock(org.springframework.context.ApplicationContext.class));
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
                new ModelRetryPolicy(1, 1, 10), null, null, null, null);
        loop.setResidentAgentRegistry(new ResidentAgentRegistry()); // 装配委托（同 Spring 容器注入）
        return loop;
    }

    private AgentRunRequest request(String text) {
        return AgentRunRequest.builder().sessionId(sessionId).userMessage(text).build();
    }

    @Test
    void delegatedRunExecutesOnResidentWorkerAndJoinsSynchronously() throws Exception {
        RecordingGateway gateway = new RecordingGateway();
        gateway.blockMs = 120;
        AgentLoopService loop = newDelegatedLoop(gateway);

        long t0 = System.nanoTime();
        AgentRunResult result = loop.run(request("第一条"));
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);

        // 同步语义不变：run 返回时模型调用已完成
        assertEquals(1, gateway.calls.get());
        assertTrue(elapsedMs >= 120, "join 等待 worker 完成，elapsedMs=" + elapsedMs);
        // 委托发生：模型调用在 resident worker（非调用线程）
        assertNotEquals(Thread.currentThread().toString(), gateway.lastCallerThread.get(),
                "run 应委托到 ResidentAgent 虚拟线程执行者");
        assertEquals("你好", result.content());
    }

    @Test
    void concurrentSameSessionRunsAreSerializedFifo() throws Exception {
        RecordingGateway gateway = new RecordingGateway();
        gateway.blockMs = 150;
        AgentLoopService loop = newDelegatedLoop(gateway);

        // A 在 worker 上进入模型调用（阻塞 150ms）；期间另一线程并发提交 B → B 应排队等 A 完成
        AtomicReference<AgentRunResult> resultA = new AtomicReference<>();
        AtomicReference<AgentRunResult> resultB = new AtomicReference<>();
        CompletableFuture<Void> aDone = CompletableFuture.runAsync(() ->
                resultA.set(loop.run(request("第一条"))));
        gateway.firstCallEntered.await(3, TimeUnit.SECONDS); // A 已在 worker 阻塞中
        CompletableFuture<Void> bDone = CompletableFuture.runAsync(() ->
                resultB.set(loop.run(request("并发第二条")))); // B 此刻入队（worker 忙）
        aDone.get(5, TimeUnit.SECONDS);
        bDone.get(5, TimeUnit.SECONDS);

        assertEquals("你好", resultA.get().content());
        assertEquals("你好", resultB.get().content());
        assertEquals(2, gateway.calls.get());
        // FIFO 串行：B 的模型调用开始不早于 A 完成（A start + 150ms 阻塞 → 二者间隔 ≥ 阻塞时长）
        assertTrue(gateway.callStartNanos.get(1) - gateway.callStartNanos.get(0) >= TimeUnit.MILLISECONDS.toNanos(140),
                "同会话并发应排队串行执行（入队序）");
    }

    @Test
    void fiveConcurrentSameSessionChatsAllSucceedSerialized() throws Exception {
        // 红线 §6-2a：同会话并发 N chat → 全部成功、按入队序串行（不再报并发写冲突）
        RecordingGateway gateway = new RecordingGateway();
        gateway.blockMs = 40;
        AgentLoopService loop = newDelegatedLoop(gateway);

        int n = 5;
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(n);
        try {
            List<CompletableFuture<AgentRunResult>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                futures.add(CompletableFuture.supplyAsync(() ->
                        loop.run(request("并发#" + Thread.currentThread().getId())), pool));
            }
            for (CompletableFuture<AgentRunResult> f : futures) {
                assertEquals("你好", f.get(8, TimeUnit.SECONDS).content());
            }
            assertEquals(n, gateway.calls.get(), "N 个 chat 全部执行（不丢不失败）");
            // 严格串行无交错：相邻模型调用起点间隔 ≥ 阻塞时长（FIFO 单执行者）
            for (int i = 1; i < n; i++) {
                long gap = gateway.callStartNanos.get(i) - gateway.callStartNanos.get(i - 1);
                assertTrue(gap >= TimeUnit.MILLISECONDS.toNanos(30),
                        "第 " + i + " 个调用应排队在前一个完成后（gap=" + gap + "ns）");
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void disabledFallbackRunsDirectlyOnCallerThread() {
        RecordingGateway gateway = new RecordingGateway();
        AgentLoopService loop = newDelegatedLoop(gateway);
        loop.setResidentEnabled(false); // D8 回退开关：false = 直接执行旧路径

        AgentRunResult result = loop.run(request("回退"));

        assertEquals("你好", result.content());
        assertEquals(Thread.currentThread().toString(), gateway.lastCallerThread.get(),
                "resident=false 应在调用线程直接执行（旧路径）");
    }

    @Test
    void streamDelegationPassesCallbacksThroughWorker() throws Exception {
        RecordingGateway gateway = new RecordingGateway();
        AgentLoopService loop = newDelegatedLoop(gateway);
        List<String> tokens = new ArrayList<>();
        AgentRunResult result = loop.stream(request("流式"), tokens::add, null);
        assertEquals("你好", result.content());
        assertEquals(1, gateway.calls.get());
        assertEquals("你好", String.join("", tokens));
        assertNotEquals(Thread.currentThread().toString(), gateway.lastCallerThread.get(),
                "stream 应同样委托到 resident worker");
    }
}
