package com.bizfty.anchon.dsh.plantask;

import com.bizfty.anchon.dsh.storage.InMemoryStorageBackend;
import com.bizfty.anchon.dsh.storage.StorageBackend;
import com.bizfty.anchon.dsh.storage.StorageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Worker 认领/派发测试：pump 自动认领 queued 任务、并发上限、执行终态回写。
 */
class PlanTaskWorkerManagerTest {

    /** 可控执行器：默认阻塞直到 release()，便于验证并发与终态，避免快速完成导致的竞态。 */
    private static final class GateExecutor implements PlanTaskExecutor {
        final CountDownLatch gate = new CountDownLatch(1);
        final AtomicInteger running = new AtomicInteger();

        @Override
        public ExecutionOutcome execute(String workspaceId, String taskId, String prompt) {
            running.incrementAndGet();
            try {
                gate.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return ExecutionOutcome.done("done:" + taskId);
        }

        void release() {
            gate.countDown();
        }
    }

    private TaskService taskService() {
        @SuppressWarnings("unchecked")
        ObjectProvider<StorageBackend> sp = mock(ObjectProvider.class);
        when(sp.orderedStream()).thenReturn(Stream.of(new InMemoryStorageBackend()));
        return new TaskService(new TaskStore(new StorageService(sp)), 60000, 180000);
    }

    @Test
    void pumpClaimsRunsThenDone() throws Exception {
        TaskService svc = taskService();
        GateExecutor exec = new GateExecutor();
        PlanTaskWorkerManager wm = new PlanTaskWorkerManager(svc, exec, 4, 200, 10000, false);
        wm.register("ws1");
        List<PlanTask> tasks = svc.submit("ws1", List.of("T1", "T2", "T3"));

        int started = wm.pump("ws1");
        assertEquals(3, started);
        // 执行器阻塞中 → 均应 running
        await(() -> svc.list("ws1").stream().allMatch(t -> PlanTask.STATUS_RUNNING.equals(t.status())));
        assertEquals(3, wm.inFlight("ws1"));

        // 释放 → 终态 done
        exec.release();
        await(() -> svc.list("ws1").stream().allMatch(t -> PlanTask.STATUS_DONE.equals(t.status())));
        assertEquals(0, wm.inFlight("ws1"));
    }

    @Test
    void concurrencyLimitRespected() throws Exception {
        TaskService svc = taskService();
        GateExecutor exec = new GateExecutor();
        PlanTaskWorkerManager wm = new PlanTaskWorkerManager(svc, exec, 2, 200, 10000, false);
        wm.register("ws1");
        List<PlanTask> tasks = svc.submit("ws1", List.of("A", "B", "C"));

        // 并发 2：第一次 pump 仅派发 2
        assertEquals(2, wm.pump("ws1"));
        assertEquals(2, wm.inFlight("ws1"));
        // 未释放 → 无空闲槽，再 pump 不领取
        assertEquals(0, wm.pump("ws1"));
        // 释放前 2 个 → 出现空闲槽 → pump 领取第 3 个
        exec.release();
        await(() -> wm.inFlight("ws1") < 2);
        assertEquals(1, wm.pump("ws1"));
        // 全部执行完成
        await(() -> svc.list("ws1").stream().allMatch(t -> PlanTask.STATUS_DONE.equals(t.status())));
        assertEquals(0, wm.inFlight("ws1"));
    }

    private static void await(java.util.function.BooleanSupplier cond) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 4000;
        while (!cond.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertTrue(cond.getAsBoolean(), "条件在超时前未满足");
    }
}
