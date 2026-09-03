package com.bizfty.anchon.dsh.session;

import com.bizfty.anchon.dsh.core.event.SessionEvent;
import com.bizfty.anchon.dsh.core.event.SessionEventBus;
import com.bizfty.anchon.dsh.core.event.SessionEventType;
import com.bizfty.anchon.dsh.core.model.Session;
import com.bizfty.anchon.dsh.core.model.SessionId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SessionSurfaceStore（M8 surfaceOp 写入）集成测试：gen 单调连续、遮蔽区间/回卷写语义、
 * no-op（replaceHead<=0）、并发 gate fail-fast（乐观锁第一道防线）、SESSION_SURFACE_CHANGED 事件。
 */
@SpringBootTest(classes = SessionSurfaceStoreTest.TestConfig.class)
class SessionSurfaceStoreTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EnableJpaRepositories(basePackageClasses = SessionSurfaceRepository.class)
    @EntityScan(basePackageClasses = SessionSurfaceEntity.class)
    @Import({SessionService.class, SessionFactStore.class, SessionSurfaceStore.class,
            com.bizfty.anchon.dsh.core.event.SessionEventBus.class})
    static class TestConfig {
    }

    @Autowired
    private SessionService sessionService;
    @Autowired
    private SessionSurfaceStore store;
    @Autowired
    private SessionEventBus eventBus;

    private SessionId sid;

    @BeforeEach
    void newSession() {
        Session s = sessionService.createSession("surface-store", "deepseek-chat", "/workspace");
        sid = s.id();
    }

    @Test
    void genIsMonotonicAndInstructionsAccumulate() {
        long g1 = store.replaceHead(sid, 8, "摘要A", "compaction");
        long g2 = store.replaceRange(sid, 3, 6, "fold", "tool-fold");
        long g3 = store.restoreRange(sid, 1, 8, "restore");

        assertEquals(1, g1);
        assertEquals(2, g2);
        assertEquals(3, g3);
        assertEquals(3, store.currentGeneration(sid));

        List<SurfaceInstruction> ops = store.listInstructions(sid);
        assertEquals(3, ops.size());
        assertEquals(SurfaceOp.REPLACE_HEAD, ops.get(0).op());
        assertEquals(1, ops.get(0).rangeFrom());
        assertEquals(8, ops.get(0).rangeTo());
        assertEquals("摘要A", ops.get(0).replacement());
        assertEquals(SurfaceOp.REPLACE_RANGE, ops.get(1).op());
        assertEquals(3, ops.get(1).rangeFrom());
        assertEquals(6, ops.get(1).rangeTo());
        assertEquals("fold", ops.get(1).replacement());
        assertEquals(SurfaceOp.APPEND_VIEW, ops.get(2).op());
        assertTrue(ops.get(2).metaJson() != null && ops.get(2).metaJson().contains("\"restores\":true"));
    }

    @Test
    void replaceHeadWithNonPositiveCountIsNoOp() {
        assertEquals(0, store.replaceHead(sid, 0, "摘要", "compaction"));
        assertEquals(0, store.replaceHead(sid, -3, "摘要", "compaction"));
        assertEquals(0, store.currentGeneration(sid));
    }

    @Test
    void invalidRangeRejected() {
        assertThrows(IllegalArgumentException.class, () -> store.replaceRange(sid, 0, 5, null, "x"));
        assertThrows(IllegalArgumentException.class, () -> store.replaceRange(sid, 6, 3, null, "x"));
        assertThrows(IllegalArgumentException.class, () -> store.restoreRange(sid, 0, 5, "x"));
    }

    @Test
    void publishesSurfaceChangedEvent() {
        List<SessionEvent> events = new CopyOnWriteArrayList<>();
        Runnable dispose = eventBus.addListener(new com.bizfty.anchon.dsh.core.event.SessionEventListener() {
            @Override
            public int order() {
                return 100;
            }

            @Override
            public void onEvent(SessionEvent ev) {
                if (ev.type() == SessionEventType.SESSION_SURFACE_CHANGED) {
                    events.add(ev);
                }
            }
        });
        try {
            store.replaceHead(sid, 4, "摘要", "compaction");
            store.replaceRange(sid, 2, 3, "fold", "tool-fold");
            assertEquals(2, events.size());
            assertEquals(SessionEventType.SESSION_SURFACE_CHANGED, events.get(0).type());
            assertEquals(1L, events.get(0).payload().get("gen"));
            assertEquals("compaction", events.get(0).payload().get("reason"));
            assertEquals("REPLACE_RANGE", events.get(1).payload().get("op"));
        } finally {
            dispose.run();
        }
    }

    @Test
    void concurrentWritesFailFastWithoutGenDup() throws Exception {
        // 同会话两线程并发写：会话行乐观锁 gate → 至多一个成功，另一个抛
        // SessionConcurrentModificationException（不产生错序/重复 gen）。
        int threads = 2;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<Long>> futures = new ArrayList<>();
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            int idx = i;
            futures.add(pool.submit(() -> {
                barrier.await();
                try {
                    long gen = store.replaceHead(sid, 5 + idx, "摘要" + idx, "compaction");
                    successes.incrementAndGet();
                    return gen;
                } catch (SessionService.SessionConcurrentModificationException e) {
                    conflicts.incrementAndGet();
                    return -1L;
                }
            }));
        }
        List<Long> gens = new ArrayList<>();
        for (Future<Long> f : futures) {
            long g = f.get();
            if (g > 0) {
                gens.add(g);
            }
        }
        pool.shutdown();

        // 红线：并发写不得产生错序/重复 gen —— 成功者 gen 连续 1..S 无重复（同 SessionFactStore 口径）。
        assertTrue(successes.get() >= 1, "至少一次成功写入");
        assertEquals(threads, successes.get() + conflicts.get(), "成功+冲突=总请求数");
        List<Long> sorted = gens.stream().sorted().toList();
        assertEquals(java.util.stream.LongStream.rangeClosed(1, successes.get()).boxed().toList(), sorted,
                "成功者 gen 必须连续 1..S 且无重复");
        List<SurfaceInstruction> ops = store.listInstructions(sid);
        assertEquals(successes.get(), ops.size());
        assertEquals(successes.get(), store.currentGeneration(sid));
    }
}
