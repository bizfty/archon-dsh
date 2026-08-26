package com.bizfty.anchon.dsh.subagent;

import com.bizfty.anchon.dsh.agent.AgentLoopService;
import com.bizfty.anchon.dsh.agent.AgentRunRequest;
import com.bizfty.anchon.dsh.agent.AgentRunResult;
import com.bizfty.anchon.dsh.core.model.Session;
import com.bizfty.anchon.dsh.core.model.SessionId;
import com.bizfty.anchon.dsh.session.SessionService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 子代理并行委托测试：多个 subagent 同时 start 时真正并发执行
 * （AgentLoopService 同时进入的执行体数量 > 1），而不是排队串行。
 * <p>
 * 对应 SubagentTool.isConcurrencySafe()=true 的语义：独立子会话、调用可交换，
 * 由 AgentLoopService 的并行池调度多个 subagent 调用。
 */
class SubagentParallelTest {

    private final SessionId parentId = SessionId.of("sess_par_sub");
    private final Session parent = new Session(parentId, "父", null, "/workspace",
            Instant.now(), Instant.now());

    @Test
    void parallelSubagentStartsOverlap() throws Exception {
        // 并发进入数：同一时刻正在执行子代理 run 的最大数量
        AtomicInteger concurrent = new AtomicInteger();
        AtomicInteger maxConcurrent = new AtomicInteger();
        CountDownLatch gate = new CountDownLatch(2);   // 等两个都进入
        CountDownLatch release = new CountDownLatch(1); // 放行
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

        AgentLoopService loop = mock(AgentLoopService.class);
        when(loop.run(any(AgentRunRequest.class))).thenAnswer(inv -> {
            int now = concurrent.incrementAndGet();
            maxConcurrent.accumulateAndGet(now, Math::max);
            try {
                gate.countDown();
                // 等待两个子代理都进入执行体，然后一起放行 — 若串行则第二个永远等不到 gate 释放
                assertTrue(release.await(5, TimeUnit.SECONDS), "两个子代理应并发进入执行体");
                return new AgentRunResult("子代理结果", ((AgentRunRequest) inv.getArgument(0)).sessionId(), 1, 0);
            } finally {
                concurrent.decrementAndGet();
            }
        });

        SessionService sessions = mock(SessionService.class);
        when(sessions.getSession(parentId)).thenReturn(parent);
        when(sessions.createSession(any(), any(), any())).thenAnswer(inv -> new Session(
                SessionId.of("sess_child_" + System.nanoTime()), inv.getArgument(0), inv.getArgument(1),
                inv.getArgument(2), Instant.now(), Instant.now()));

        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<AgentLoopService> provider =
                mock(org.springframework.beans.factory.ObjectProvider.class);
        when(provider.getObject()).thenReturn(loop);
        SubagentRunner runner = new SubagentRunner(provider, sessions, new SubagentRegistry(), 3);

        // 同时启动两个子代理（模拟模型一轮发两个 subagent 调用，由并行池调度）
        var f1 = executor.submit(() -> runner.start(parentId, "任务A", 0, null));
        var f2 = executor.submit(() -> runner.start(parentId, "任务B", 0, null));

        // 等两个都进入执行体（它们互相等待 release，串行必死锁 → 超时即失败）
        assertTrue(gate.await(5, TimeUnit.SECONDS), "两个子代理应在 5s 内都进入执行体（串行会超时）");
        release.countDown();

        var r1 = f1.get(5, TimeUnit.SECONDS);
        var r2 = f2.get(5, TimeUnit.SECONDS);

        assertEquals("子代理结果", r1.content());
        assertEquals("子代理结果", r2.content());
        assertEquals(2, maxConcurrent.get(), "两个子代理应并发执行（最大并发数 = 2）");
        executor.shutdown();
    }
}
