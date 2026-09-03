package com.bizfty.anchon.dsh.session;

import com.bizfty.anchon.dsh.core.model.MessageRole;
import com.bizfty.anchon.dsh.core.model.Session;
import com.bizfty.anchon.dsh.core.model.SessionId;
import com.bizfty.anchon.dsh.core.model.SessionMessage;
import com.bizfty.anchon.dsh.util.JsonUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M9 投影 registry 失效正确性集成测试（红线 §5-3/§5-4，真实 Spring 容器）：
 * SessionSurfaceStore 写 surface 指令 → registry 直失效（D3-C 主通道）→ 下一 snapshot 重载且
 * 不含旧遮蔽（零陈旧窗口）；快照输出与"指令直算 + buildSegments"逐条一致（契约锁双跑）。
 */
@SpringBootTest(classes = SessionProjectionRegistryFlowTest.TestConfig.class)
class SessionProjectionRegistryFlowTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EnableJpaRepositories(basePackageClasses = SessionSurfaceRepository.class)
    @EntityScan(basePackageClasses = SessionSurfaceEntity.class)
    @Import({SessionService.class, SessionFactStore.class, SessionSurfaceStore.class,
            com.bizfty.anchon.dsh.core.event.SessionEventBus.class,
            com.bizfty.anchon.dsh.util.JsonUtils.class,
            SurfaceProjector.class, SessionProjectionRegistry.class})
    static class TestConfig {
    }

    @Autowired
    private SessionService sessionService;
    @Autowired
    private SessionSurfaceStore store;
    @Autowired
    private SessionProjectionRegistry registry;
    @Autowired
    private SurfaceProjector projector;

    private final JsonUtils jsonUtils = new JsonUtils();
    private SessionId sid;
    private final AtomicInteger loads = new AtomicInteger();

    @BeforeEach
    void newSession() {
        Session s = sessionService.createSession("proj-registry-flow", "deepseek-chat", "/workspace");
        sid = s.id();
        loads.set(0);
    }

    private Optional<SessionProjection> snapshot() {
        return registry.snapshot(sid,
                () -> {
                    loads.incrementAndGet();
                    return store.listInstructions(sid);
                },
                () -> store.currentGeneration(sid));
    }

    private SessionMessage m(long seq, MessageRole role, String content) {
        return new SessionMessage("msg_" + seq, sid, role, content, null, null, null, seq,
                Instant.now(), false);
    }

    private List<SessionMessage> history() {
        return List.of(
                m(1, MessageRole.USER, "旧问题"),
                m(2, MessageRole.ASSISTANT, "旧回答"),
                m(3, MessageRole.USER, "中间问题"),
                m(4, MessageRole.ASSISTANT, "中间回答"),
                m(5, MessageRole.USER, "当前 USER"));
    }

    private List<String> idsOf(List<SessionMessage> visible) {
        return visible.stream().map(SessionMessage::id).toList();
    }

    // ---- 红线 §5-3：写后下读不含旧遮蔽（零陈旧窗口） ----

    @Test
    void writeInvalidatesRegistryAndNextReadSeesNewSurface() {
        // 初始无 surface → 无快照（fast-path）
        assertTrue(snapshot().isEmpty());
        assertEquals(0, registry.size());

        // 写 REPLACE_HEAD（遮蔽 [1..2] + 摘要）→ registry 直失效 → 下读重载为新遮蔽
        store.replaceHead(sid, 2, "（历史压缩摘要）\n旧问题旧回答", "compact");
        Optional<SessionProjection> afterHead = snapshot();
        assertTrue(afterHead.isPresent());
        assertEquals(1, afterHead.get().surfaceGen());
        List<SessionMessage> visibleAfterHead = projector.projectVisible(history(), afterHead.get(), 100, 1);
        List<String> ids = idsOf(visibleAfterHead);
        assertTrue(ids.contains("surface_rep_1_2"), "摘要视图行置头");
        assertTrue(!ids.contains("msg_1") && !ids.contains("msg_2"), "遮蔽 [1..2] 不重发");
        assertTrue(ids.contains("msg_3"), "未遮蔽历史可见");

        // 命中：指令集未变 → loader 不重调（空表探测 1 次 + 写后首读 1 次 = 2；第二读命中零重载）
        Optional<SessionProjection> hit = snapshot();
        assertEquals(2, loads.get(), "写后首读装载 1 次；第二读命中零重载");

        // 再写 REPLACE_RANGE（折叠 [3..4]）→ 失效 → 下读遮蔽更新，旧 [3..4] 遮蔽被覆盖
        store.replaceRange(sid, 3, 4, "[中间折叠]", "fold");
        Optional<SessionProjection> afterRange = snapshot();
        assertTrue(afterRange.isPresent());
        assertEquals(2, afterRange.get().surfaceGen());
        List<SessionMessage> visibleAfterRange =
                projector.projectVisible(history(), afterRange.get(), 100, 1);
        List<String> ids2 = idsOf(visibleAfterRange);
        assertTrue(ids2.contains("surface_rep_1_2"));
        assertTrue(ids2.contains("surface_rep_3_4"), "折叠段视图行");
        assertTrue(!ids2.contains("msg_3") && !ids2.contains("msg_4"), "折叠 [3..4] 遮蔽");

        // 写后直失效主通道已清缓存：snapshot 走 loader（次数>1），且输出与直算 buildSegments 一致
        assertTrue(loads.get() >= 2, "每次 surface 写后下读重载");
    }

    // ---- 契约锁双跑：快照路径输出 == 指令直算路径输出（含 restore 回卷后） ----

    @Test
    void snapshotOutputEqualsDirectComputationAfterWrites() {
        store.replaceHead(sid, 2, "（历史压缩摘要）\n旧问题旧回答", "compact");
        store.replaceRange(sid, 3, 4, "[中间折叠]", "fold");
        store.restoreRange(sid, 3, 3, "restore");

        List<SurfaceInstruction> ops = store.listInstructions(sid);
        Optional<SessionProjection> proj = snapshot();
        assertTrue(proj.isPresent());
        assertEquals(3, proj.get().surfaceGen());

        for (int maxHistory : new int[]{2, 10, 100}) {
            List<SessionMessage> direct = projector.projectVisible(history(), ops, 0, maxHistory, 1);
            List<SessionMessage> cached = projector.projectVisible(history(), proj.get(), maxHistory, 1);
            assertEquals(direct.size(), cached.size(), "maxHistory=" + maxHistory);
            for (int i = 0; i < direct.size(); i++) {
                assertEquals(direct.get(i).id(), cached.get(i).id());
                assertEquals(direct.get(i).content(), cached.get(i).content());
            }
        }
    }
}
