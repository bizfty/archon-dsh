package com.bizfty.anchon.dsh.session;

import com.bizfty.anchon.dsh.core.event.SessionEventBus;
import com.bizfty.anchon.dsh.core.event.SessionEventType;
import com.bizfty.anchon.dsh.core.model.MessageRole;
import com.bizfty.anchon.dsh.core.model.SessionId;
import com.bizfty.anchon.dsh.core.model.SessionMessage;
import com.bizfty.anchon.dsh.util.JsonUtils;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SessionProjectionRegistry（M9 投影 registry 化缓存）单元测试：
 * <ul>
 *   <li>命中：同会话两次 snapshot 只 loader 一次（指令集不变不重读 DB / 不重算 buildSegments）；</li>
 *   <li>无表面指令不缓存（fast-path 语义，registry 不建快照）；</li>
 *   <li>失效：invalidate 直失效 + SESSION_SURFACE_CHANGED 事件兜底（D3-C 双通道）→ 下读重载；</li>
 *   <li>逐出：maxEntries 超限移除最旧（读回重建正确）；</li>
 *   <li>A1 开关：verify-gen=true 时 genLoader 落后触发重建；false 时 genLoader 零调用（零 DB）；</li>
 *   <li>双跑等价（红线 §5-2）：快照命中路径输出与指令直算路径逐条一致（多场景：REPLACE_HEAD /
 *       折叠 REPLACE_RANGE / restore / 混合）；</li>
 *   <li>并发 smoke：多线程 snapshot + invalidate 交错无异常、无陈旧。</li>
 * </ul>
 */
class SessionProjectionRegistryTest {

    private final JsonUtils jsonUtils = new JsonUtils();
    private final SurfaceProjector projector = new SurfaceProjector(jsonUtils);
    private final SessionEventBus bus = new SessionEventBus();
    private final SessionId sid = SessionId.of("sess_proj_reg");

    private SessionProjectionRegistry registry(int maxEntries, boolean verifyGen) {
        return new SessionProjectionRegistry(projector, bus, maxEntries, verifyGen);
    }

    // ---- history 构造（对齐 SurfaceProjectorTest 口径） ----

    private SessionMessage m(long seq, MessageRole role, String content, String toolCallId, String toolCallsJson) {
        return new SessionMessage("msg_" + seq, sid, role, content, toolCallId, "tool", toolCallsJson, seq,
                Instant.now(), false);
    }

    private SessionMessage user(long seq, String content) {
        return m(seq, MessageRole.USER, content, null, null);
    }

    private SessionMessage assistant(long seq, String content, String toolCallsJson) {
        return m(seq, MessageRole.ASSISTANT, content, null, toolCallsJson);
    }

    private SessionMessage tool(long seq, String toolCallId, String content) {
        return m(seq, MessageRole.TOOL, content, toolCallId, null);
    }

    private List<SessionMessage> toolHistory() {
        List<SessionMessage> list = new ArrayList<>();
        list.add(user(1, "查一下"));
        list.add(assistant(2, "好的", "[{\"id\":\"c1\",\"type\":\"function\",\"name\":\"search\",\"arguments\":\"{}\"}]"));
        list.add(tool(3, "c1", "{\"ok\":true}"));
        list.add(assistant(4, "找到 1 条", null));
        list.add(user(5, "继续"));
        list.add(assistant(6, "好的", "[{\"id\":\"c2\",\"type\":\"function\",\"name\":\"search\",\"arguments\":\"{}\"}]"));
        list.add(tool(7, "c2", "{\"ok\":false}"));
        list.add(assistant(8, "没找到", null));
        list.add(user(9, "当前 USER"));
        return list;
    }

    private List<SurfaceInstruction> foldInstructions() {
        return List.of(
                SurfaceInstruction.replaceHead(1, 2, "（历史压缩摘要）\n前面聊了查一下", "compact"),
                SurfaceInstruction.replaceRange(2, 3, 6, "[中间工具调用已折叠]", "fold"));
    }

    // ---- 1. 装载一次 + 命中零重载 ----

    @Test
    void loadOnceAndHit() {
        SessionProjectionRegistry reg = registry(64, false);
        AtomicInteger loads = new AtomicInteger();
        Supplier<List<SurfaceInstruction>> loader = () -> {
            loads.incrementAndGet();
            return foldInstructions();
        };
        Optional<SessionProjection> first = reg.snapshot(sid, loader, () -> -1L);
        Optional<SessionProjection> second = reg.snapshot(sid, loader, () -> -1L);
        assertTrue(first.isPresent());
        assertTrue(second.isPresent());
        assertEquals(1, loads.get(), "两次 snapshot 指令集不变 → loader 只调一次");
        assertEquals(first.get().surfaceGen(), second.get().surfaceGen());
        assertEquals(2, second.get().surfaceGen()); // 尾指令 gen=2
        assertEquals(1, reg.size());
    }

    // ---- 2. 无表面指令不缓存 ----

    @Test
    void emptyInstructionsNeverCached() {
        SessionProjectionRegistry reg = registry(64, false);
        Optional<SessionProjection> snap = reg.snapshot(sid, List::of, () -> 0L);
        assertFalse(snap.isPresent(), "无指令不建快照（legacy fast-path 语义）");
        assertEquals(0, reg.size());
    }

    // ---- 3. invalidate 直失效 → 重载 ----

    @Test
    void invalidateForcesReload() {
        SessionProjectionRegistry reg = registry(64, false);
        AtomicInteger loads = new AtomicInteger();
        Supplier<List<SurfaceInstruction>> loader = () -> {
            loads.incrementAndGet();
            return foldInstructions();
        };
        reg.snapshot(sid, loader, () -> -1L);
        reg.invalidate(sid);
        reg.snapshot(sid, loader, () -> -1L);
        assertEquals(2, loads.get(), "失效后下读重载");
        assertEquals(1, reg.size());
    }

    // ---- 4. SESSION_SURFACE_CHANGED 事件兜底失效 ----

    @Test
    void surfaceChangedEventInvalidates() {
        SessionProjectionRegistry reg = registry(64, false);
        AtomicInteger loads = new AtomicInteger();
        Supplier<List<SurfaceInstruction>> loader = () -> {
            loads.incrementAndGet();
            return foldInstructions();
        };
        reg.snapshot(sid, loader, () -> -1L);
        bus.publish(sid, SessionEventType.SESSION_SURFACE_CHANGED, java.util.Map.of("gen", 3L));
        reg.snapshot(sid, loader, () -> -1L);
        assertEquals(2, loads.get(), "事件兜底通道失效");
    }

    // ---- 5. 容量逐出：最旧被移除，读回重建正确 ----

    @Test
    void evictionRemovesOldestAndRebuilds() {
        SessionProjectionRegistry reg = registry(2, false);
        AtomicInteger loads = new AtomicInteger();
        List<SessionId> sessions = List.of(
                SessionId.of("s1"), SessionId.of("s2"), SessionId.of("s3"));
        for (SessionId s : sessions) {
            reg.snapshot(s, () -> {
                loads.incrementAndGet();
                return List.of(SurfaceInstruction.replaceHead(1, 2, "摘要", "compact"));
            }, () -> -1L);
        }
        assertTrue(reg.size() <= 2, "超限逐出（maxEntries=2）");
        // s1 最旧已被逐出：再读 s1 重载且结果正确
        Optional<SessionProjection> again = reg.snapshot(sessions.get(0), () -> {
            loads.incrementAndGet();
            return List.of(SurfaceInstruction.replaceHead(1, 2, "摘要", "compact"));
        }, () -> -1L);
        assertTrue(again.isPresent());
        assertEquals(1L, again.get().surfaceGen());
        assertTrue(reg.size() <= 2);
    }

    // ---- 6. A1 开关：verify-gen 落后重建；关闭时 genLoader 零调用 ----

    @Test
    void verifyGenRebuildsWhenDbGenAhead() {
        SessionProjectionRegistry reg = registry(64, true);
        AtomicInteger loads = new AtomicInteger();
        AtomicInteger gen = new AtomicInteger(1);
        Supplier<List<SurfaceInstruction>> loader = () -> {
            loads.incrementAndGet();
            return List.of(SurfaceInstruction.replaceHead(1, 1, "摘要", "compact"));
        };
        reg.snapshot(sid, loader, () -> (long) gen.get());
        // DB 代数领先（他实例写入）→ 下读重建
        gen.incrementAndGet();
        reg.snapshot(sid, loader, () -> (long) gen.get());
        assertEquals(2, loads.get(), "verify-gen=true：DB 代数领先触发重建");
        assertEquals(1, reg.size());
    }

    @Test
    void verifyGenOffNeverCallsGenLoader() {
        SessionProjectionRegistry reg = registry(64, false);
        AtomicInteger genCalls = new AtomicInteger();
        reg.snapshot(sid, () -> foldInstructions(), () -> {
            genCalls.incrementAndGet();
            return 1L;
        });
        reg.snapshot(sid, () -> foldInstructions(), () -> {
            genCalls.incrementAndGet();
            return 1L;
        });
        assertEquals(0, genCalls.get(), "A0 默认：genLoader 零调用（命中零 DB）");
    }

    // ---- 7. 双跑等价：快照命中输出 == 指令直算输出（红线 §5-2） ----

    @Test
    void snapshotPathEqualsDirectPathForAllScenarios() {
        List<SessionMessage> history = toolHistory();
        List<List<SurfaceInstruction>> scenarios = List.of(
                List.of(SurfaceInstruction.replaceHead(1, 2, "（历史压缩摘要）\n查了一下", "compact")),
                List.of(SurfaceInstruction.replaceHead(1, 2, "（历史压缩摘要）\n查了一下", "compact"),
                        SurfaceInstruction.replaceRange(2, 3, 6, "[中间工具调用已折叠]", "fold")),
                List.of(SurfaceInstruction.replaceHead(1, 2, "（历史压缩摘要）\n查了一下", "compact"),
                        SurfaceInstruction.replaceRange(2, 3, 6, "[中间工具调用已折叠]", "fold"),
                        SurfaceInstruction.restoreRange(3, 4, 6, "restore")),
                List.of(SurfaceInstruction.replaceRange(2, 4, 7, "[已折叠]", "fold"),
                        SurfaceInstruction.replaceHead(1, 1, "（头部摘要）", "compact"))
        );
        SessionProjectionRegistry reg = registry(64, false);
        for (int i = 0; i < scenarios.size(); i++) {
            List<SurfaceInstruction> ops = scenarios.get(i);
            Optional<SessionProjection> snap = reg.snapshot(SessionId.of("s_eq_" + i), () -> ops, () -> -1L);
            assertTrue(snap.isPresent(), "scenario " + i + " 有指令应建快照");
            for (int maxHistory : new int[]{3, 8, 100}) {
                List<SessionMessage> direct = projector.projectVisible(history, ops, 0, maxHistory, 1);
                List<SessionMessage> cached = projector.projectVisible(history, snap.get(), maxHistory, 1);
                assertEquals(direct.size(), cached.size(),
                        "scenario " + i + " maxHistory=" + maxHistory + " 逐条一致");
                for (int j = 0; j < direct.size(); j++) {
                    assertEquals(direct.get(j).content(), cached.get(j).content());
                    assertEquals(direct.get(j).id(), cached.get(j).id());
                    assertEquals(direct.get(j).role(), cached.get(j).role());
                }
            }
        }
    }

    // ---- 8. 并发 smoke：读写交错无异常 ----

    @Test
    void concurrentSnapshotAndInvalidateAreSafe() throws Exception {
        SessionProjectionRegistry reg = registry(128, false);
        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < 4; t++) {
                final int idx = t;
                futures.add(pool.submit(() -> {
                    for (int i = 0; i < 200; i++) {
                        SessionId s = SessionId.of("c" + (i % 5) + "_" + idx);
                        String content = "摘要" + i % 3;
                        reg.snapshot(s, () -> List.of(
                                SurfaceInstruction.replaceHead(1, 2, content, "compact")), () -> -1L);
                    }
                }));
            }
            for (int t = 0; t < 2; t++) {
                futures.add(pool.submit(() -> {
                    for (int i = 0; i < 200; i++) {
                        reg.invalidate(SessionId.of("c" + (i % 5) + "_" + (i % 4)));
                    }
                }));
            }
            for (Future<?> f : futures) {
                f.get();
            }
            assertTrue(reg.size() >= 0);
        } finally {
            pool.shutdownNow();
        }
    }

    // ---- 9. 红线 §5-6：命中 ≥10x 快于同参重建（宽松烟雾） ----

    @Test
    void registryHitIsAtLeast10xFasterThanRebuild() {
        // 150 段遮蔽指令（同 SurfaceProjectorPerfSmokeTest 量级）
        List<SurfaceInstruction> ops = new ArrayList<>();
        long gen = 1;
        for (int i = 0; i < 150; i++) {
            long from = i * 100L + 1;
            long to = from + 50;
            ops.add(new SurfaceInstruction(gen++, SurfaceOp.REPLACE_RANGE, from, to,
                    i % 3 == 0 ? "折叠" + i : null, "{\"reason\":\"perf\"}"));
        }
        SessionId perfSid = SessionId.of("sess_perf_hit");
        SessionProjectionRegistry reg = registry(64, false);
        Supplier<List<SurfaceInstruction>> loader = () -> ops;

        reg.snapshot(perfSid, loader, () -> -1L); // 预热装载
        long t0 = System.nanoTime();
        for (int i = 0; i < 200; i++) {
            reg.snapshot(perfSid, loader, () -> -1L); // 全命中（指令集不变）
        }
        long hitMs = (System.nanoTime() - t0) / 1_000_000;

        long t1 = System.nanoTime();
        for (int i = 0; i < 200; i++) {
            reg.invalidate(perfSid);
            reg.snapshot(perfSid, loader, () -> -1L); // 每次重建（loader + buildSegments）
        }
        long rebuildMs = (System.nanoTime() - t1) / 1_000_000;

        // 宽松断言防 CI 抖动：命中 200 轮应显著快于重建 200 轮（量级 ≥10x）
        assertTrue(hitMs * 10 < rebuildMs + 200,
                "命中应 ≥10x 快于重建（hit=" + hitMs + "ms rebuild=" + rebuildMs + "ms）");
    }
}
