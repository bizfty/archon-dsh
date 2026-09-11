package com.bizfty.anchon.dsh.plantask;

import com.bizfty.anchon.dsh.storage.InMemoryStorageBackend;
import com.bizfty.anchon.dsh.storage.StorageService;
import com.bizfty.anchon.dsh.storage.StorageBackend;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 计划任务服务测试：批量提交 / 列表 / 领取(CAS) / 状态流转 / 重跑 / 重启恢复。
 */
class TaskServiceTest {

    private TaskService service() {
        @SuppressWarnings("unchecked")
        ObjectProvider<StorageBackend> sp = mock(ObjectProvider.class);
        when(sp.orderedStream()).thenReturn(Stream.of(new InMemoryStorageBackend()));
        return new TaskService(new TaskStore(new StorageService(sp)), 60000, 180000);
    }

    @Test
    void batchSubmitListsQueued() {
        TaskService svc = service();
        List<PlanTask> created = svc.submit("ws1", List.of("写测试 A", "修复 B", ""));
        assertEquals(2, created.size());
        assertTrue(created.stream().allMatch(t -> PlanTask.STATUS_QUEUED.equals(t.status())));

        List<PlanTask> listed = svc.list("ws1");
        assertEquals(2, listed.size());
        assertEquals("写测试 A", listed.get(0).prompt());
        // 其他工作区隔离
        assertTrue(svc.list("ws2").isEmpty());
    }

    @Test
    void claimNextIsCasAndExclusive() {
        TaskService svc = service();
        List<PlanTask> created = svc.submit("ws1", List.of("T1", "T2"));
        Optional<PlanTask> claimed = svc.claimNext("ws1", "worker-1");
        assertTrue(claimed.isPresent());
        assertEquals(PlanTask.STATUS_CLAIMED, claimed.get().status());
        assertEquals("worker-1", claimed.get().claimedBy());
        // 已被领取的任务不再被重复领取
        Optional<PlanTask> again = svc.claimNext("ws1", "worker-2");
        assertTrue(again.isPresent());
        assertEquals("T2", again.get().prompt());
        // 全部领取后返回 empty
        assertTrue(svc.claimNext("ws1", "worker-1").isEmpty());
    }

    @Test
    void updateStatusFlow() {
        TaskService svc = service();
        PlanTask t = svc.submit("ws1", List.of("T")).get(0);
        svc.claimNext("ws1", "w");
        Optional<PlanTask> running = svc.updateStatus("ws1", t.id(), PlanTask.STATUS_CLAIMED,
                PlanTask.STATUS_RUNNING, "w", "sess-1", null, null);
        assertTrue(running.isPresent());
        assertEquals(PlanTask.STATUS_RUNNING, running.get().status());
        assertEquals("sess-1", running.get().executorSessionId());

        Optional<PlanTask> done = svc.updateStatus("ws1", t.id(), PlanTask.STATUS_RUNNING,
                PlanTask.STATUS_DONE, "w", "sess-1", "ok", null);
        assertTrue(done.isPresent());
        assertEquals(PlanTask.STATUS_DONE, done.get().status());
        assertTrue(done.get().terminal());
        // 错误前置状态流转应失败
        assertTrue(svc.updateStatus("ws1", t.id(), PlanTask.STATUS_RUNNING,
                PlanTask.STATUS_DONE, "w", "sess-1", "x", null).isEmpty());
    }

    @Test
    void rerunReturnsToQueued() {
        TaskService svc = service();
        PlanTask t = svc.submit("ws1", List.of("T")).get(0);
        svc.claimNext("ws1", "w");
        svc.updateStatus("ws1", t.id(), PlanTask.STATUS_CLAIMED, PlanTask.STATUS_FAILED,
                "w", "s", null, "boom");
        Optional<PlanTask> rerun = svc.rerun("ws1", t.id());
        assertTrue(rerun.isPresent());
        assertEquals(PlanTask.STATUS_QUEUED, rerun.get().status());
        assertEquals(2, rerun.get().attempt());
        assertTrue(rerun.get().claimedBy() == null);
    }

    @Test
    void recoverOrphansResetsToQueued() {
        TaskService svc = service();
        PlanTask t = svc.submit("ws1", List.of("T")).get(0);
        svc.claimNext("ws1", "w");
        svc.updateStatus("ws1", t.id(), PlanTask.STATUS_CLAIMED, PlanTask.STATUS_RUNNING,
                "w", "sess-1", null, null);
        // 模拟重启后 running 任务成为孤儿
        int recovered = svc.recoverOrphans("ws1");
        assertEquals(1, recovered);
        PlanTask after = svc.get("ws1", t.id()).orElseThrow();
        assertEquals(PlanTask.STATUS_QUEUED, after.status());
    }
}
