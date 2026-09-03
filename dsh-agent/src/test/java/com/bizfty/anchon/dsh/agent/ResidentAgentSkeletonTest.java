package com.bizfty.anchon.dsh.agent;

import com.bizfty.anchon.dsh.core.model.SessionId;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * M4-1 ResidentAgent/Registry 骨架（design §4.1/§4.4，红线 §6-2/§6-5 前置）：
 * FIFO 顺序、同会话串行（单执行者）、跨会话并行、Phase 迁移、异常/自愈、abort 标志。
 */
class ResidentAgentSkeletonTest {

    private static SessionId sid(String s) {
        return SessionId.of("sess_" + s);
    }

    private static void awaitPhase(ResidentAgent agent, ResidentPhase expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (agent.phase() != expected) {
            if (System.nanoTime() > deadline) {
                fail("phase 未达 " + expected + "，当前 " + agent.phase());
            }
            Thread.sleep(5);
        }
    }

    @Test
    void sameSessionFifoOrderOnSingleWorkerThread() throws Exception {
        try (ResidentAgent agent = new ResidentAgent(sid("fifo"))) {
            List<String> order = new ArrayList<>();
            List<String> threads = new ArrayList<>();
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (int i = 1; i <= 5; i++) {
                int idx = i;
                futures.add(agent.submit("t" + i, () -> {
                    Thread.sleep(10);
                    order.add("job" + idx);
                    threads.add(Thread.currentThread().toString());
                    return null;
                }));
            }
            for (CompletableFuture<Void> f : futures) {
                f.get(5, TimeUnit.SECONDS);
            }
            assertEquals(List.of("job1", "job2", "job3", "job4", "job5"), order, "同会话 FIFO");
            assertEquals(1, threads.stream().distinct().count(), "同会话单一执行者（虚拟线程 worker）");
        }
    }

    @Test
    void crossSessionAgentsRunInParallel() throws Exception {
        ResidentAgentRegistry registry = new ResidentAgentRegistry();
        try {
            ResidentAgent a = registry.agent(sid("para-a"));
            ResidentAgent b = registry.agent(sid("para-b"));
            long t0 = System.nanoTime();
            CompletableFuture<Void> fa = a.submit("a", () -> {
                Thread.sleep(250);
                return null;
            });
            CompletableFuture<Void> fb = b.submit("b", () -> {
                Thread.sleep(250);
                return null;
            });
            fa.get(5, TimeUnit.SECONDS);
            fb.get(5, TimeUnit.SECONDS);
            long elapsedMs = Duration.ofNanos(System.nanoTime() - t0).toMillis();
            // 串行 ≥500ms；并行 ~250ms（容差放 420ms 防 CI 抖动）
            assertTrue(elapsedMs < 420, "跨会话应并行，elapsedMs=" + elapsedMs);
        } finally {
            registry.release(sid("para-a"));
            registry.release(sid("para-b"));
        }
    }

    @Test
    void phaseTransitionsIdleQueuedRunningQueuedIdle() throws Exception {
        ResidentAgentRegistry registry = new ResidentAgentRegistry();
        try {
            ResidentAgent agent = registry.agent(sid("phase"));
            assertEquals(ResidentPhase.IDLE, agent.phase());

            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch releaseLatch = new CountDownLatch(1);
            CompletableFuture<Void> first = agent.submit("blocker", () -> {
                startLatch.countDown();
                releaseLatch.await(5, TimeUnit.SECONDS);
                return null;
            });
            CompletableFuture<Void> second = agent.submit("queued", () -> null);

            startLatch.await(5, TimeUnit.SECONDS);
            // 单执行者：第一个在跑、第二个排队 → RUNNING（pending>0）
            awaitPhase(agent, ResidentPhase.RUNNING);

            releaseLatch.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
            awaitPhase(agent, ResidentPhase.IDLE);
        } finally {
            registry.release(sid("phase"));
        }
    }

    @Test
    void errorFutureCompletesExceptionallyThenNextSubmitSelfHeals() throws Exception {
        ResidentAgentRegistry registry = new ResidentAgentRegistry();
        try {
            ResidentAgent agent = registry.agent(sid("err"));
            CompletableFuture<String> bad = agent.submit("boom", () -> {
                throw new IllegalStateException("模拟 turn 异常");
            });
            ExecutionException ex = assertThrows(ExecutionException.class,
                    () -> bad.get(5, TimeUnit.SECONDS));
            assertTrue(ex.getCause() instanceof IllegalStateException);
            awaitPhase(agent, ResidentPhase.ERROR);

            // 自愈：下次 submit 正常执行并回 IDLE
            CompletableFuture<String> ok = agent.submit("retry", () -> "fine");
            assertEquals("fine", ok.get(5, TimeUnit.SECONDS));
            awaitPhase(agent, ResidentPhase.IDLE);
        } finally {
            registry.release(sid("err"));
        }
    }

    @Test
    void abortFlagIsCooperativeAndResettable() throws Exception {
        ResidentAgentRegistry registry = new ResidentAgentRegistry();
        try {
            ResidentAgent agent = registry.agent(sid("abort"));
            assertFalse(agent.isAbortRequested());
            agent.abort();
            assertTrue(agent.isAbortRequested());
            // abort 不中断/不清空（协作语义，用户消息不丢）；无任务在跑 → 停驻 ABORTED
            assertEquals(ResidentPhase.ABORTED, agent.phase());
            agent.resetAbort();
            assertFalse(agent.isAbortRequested());
            CompletableFuture<String> ok = agent.submit("post-abort", () -> "done");
            assertEquals("done", ok.get(5, TimeUnit.SECONDS));
        } finally {
            registry.release(sid("abort"));
        }
    }

    @Test
    void releaseCancelsQueuedTasksAndLetsRunningFinish() throws Exception {
        ResidentAgentRegistry registry = new ResidentAgentRegistry();
        ResidentAgent agent = registry.agent(sid("rel"));
        CountDownLatch releaseLatch = new CountDownLatch(1);
        CompletableFuture<Void> blocker = agent.submit("blocker", () -> {
            releaseLatch.await(5, TimeUnit.SECONDS);
            return null;
        });
        CompletableFuture<Void> queued = agent.submit("will-cancel", () -> null);
        Thread.sleep(30); // 让 blocker 先被 worker 取走，will-cancel 留在队列
        registry.release(sid("rel"));

        assertThrows(ExecutionException.class, () -> queued.get(5, TimeUnit.SECONDS),
                "排队未执行任务应被取消");
        assertFalse(registry.agents().containsKey(sid("rel")), "release 后注册表应移除");

        releaseLatch.countDown();
        blocker.get(5, TimeUnit.SECONDS); // 运行中任务不被中断，自然结束（协作语义）
    }
}
