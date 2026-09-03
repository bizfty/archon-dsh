package com.bizfty.anchon.dsh.session;

import com.bizfty.anchon.dsh.core.event.SessionEvent;
import com.bizfty.anchon.dsh.core.event.SessionEventBus;
import com.bizfty.anchon.dsh.core.event.SessionEventType;
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
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M8 全能力端到端（dsh-session 集成）：真实 fact+投影 append → SessionSurfaceStore 写入
 * replaceRange/restoreRange → SurfaceProjector 可见序列 —— 断言：
 * <ul>
 *   <li>红线3：REPLACE_RANGE 遮蔽任意段 + 折叠 replacement 后可见序列 = 头 + 视图行 + 尾；</li>
 *   <li>红线4：restoreRange 后该段重新可见；</li>
 *   <li>无损：surface 操作后 fact/投影消息行数与内容不变（surface 只作用于可见视图）；</li>
 *   <li>审计事件 SESSION_SURFACE_CHANGED 发布。</li>
 * </ul>
 */
@SpringBootTest(classes = SessionSurfaceFullFlowTest.TestConfig.class)
class SessionSurfaceFullFlowTest {

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
    private SessionSurfaceStore surfaceStore;
    @Autowired
    private SessionEventBus eventBus;

    private final SurfaceProjector projector = new SurfaceProjector(new JsonUtils());
    private SessionId sid;

    @BeforeEach
    void newSessionWith8Messages() {
        Session s = sessionService.createSession("surface-full-flow", "deepseek-chat", "/workspace");
        sid = s.id();
        sessionService.append(sid, MessageRole.USER, "q1", null, null, null);
        sessionService.append(sid, MessageRole.ASSISTANT, "查一下", null, null,
                "[{\"id\":\"c1\",\"name\":\"search\",\"arguments\":\"{}\"}]");
        sessionService.append(sid, MessageRole.TOOL, "{\"ok\":true}", "c1", "search", null);
        sessionService.append(sid, MessageRole.ASSISTANT, "回答1", null, null, null);
        sessionService.append(sid, MessageRole.USER, "q2", null, null, null);
        sessionService.append(sid, MessageRole.ASSISTANT, "再查", null, null,
                "[{\"id\":\"c2\",\"name\":\"search\",\"arguments\":\"{}\"}]");
        sessionService.append(sid, MessageRole.TOOL, "{\"ok\":false}", "c2", "search", null);
        sessionService.append(sid, MessageRole.ASSISTANT, "回答2", null, null, null);
    }

    private List<SessionMessage> allMessages() {
        return sessionService.listMessages(sid);
    }

    private List<SessionMessage> visible() {
        return projector.projectVisible(allMessages(), surfaceStore.listInstructions(sid), 0, 100, 0);
    }

    @Test
    void replaceRangeThenRestoreKeepsFactAndProjectionIntact() {
        // 无损基线：8 条
        assertEquals(8, allMessages().size());

        // 折叠中间 [3..6]（TOOL c1 + 回答1 + q2 + 再查）
        long g1 = surfaceStore.replaceRange(sid, 3, 6, "[中间工具调用已折叠]", "tool-fold");
        assertEquals(1, g1);
        List<SessionMessage> afterFold = visible();
        // 头(1,2) + 视图行 + 回答2 —— 共 4 条：seq7 的 TOOL(c2) 因折叠区间把其 assistant(c2, seq6)
        // 一并遮蔽而变孤立，被配对过滤跳过（防 400；遮蔽与配对协同语义正确）。
        assertEquals(4, afterFold.size());
        assertEquals("q1", afterFold.get(0).content());
        assertEquals("查一下", afterFold.get(1).content());
        assertTrue(afterFold.get(2).id().startsWith("surface_rep_"));
        assertEquals("[中间工具调用已折叠]", afterFold.get(2).content());
        assertEquals("回答2", afterFold.get(3).content());

        // 无损：fact/投影行仍 8 条且原文保留
        List<SessionMessage> msgs = allMessages();
        assertEquals(8, msgs.size());
        assertEquals("{\"ok\":true}", msgs.get(2).content(), "被折叠 TOOL 原文保留");

        // restore → 全恢复
        long g2 = surfaceStore.restoreRange(sid, 3, 6, "restore");
        assertEquals(2, g2);
        assertEquals(8, visible().size());
        assertEquals(8, allMessages().size(), "restore 后 fact/投影仍无损");
    }

    @Test
    void surfaceWritePublishesAuditEvents() {
        List<SessionEvent> events = new CopyOnWriteArrayList<>();
        Runnable dispose = eventBus.addListener(new com.bizfty.anchon.dsh.core.event.SessionEventListener() {
            @Override
            public int order() {
                return 50;
            }

            @Override
            public void onEvent(SessionEvent ev) {
                if (ev.type() == SessionEventType.SESSION_SURFACE_CHANGED) {
                    events.add(ev);
                }
            }
        });
        try {
            surfaceStore.replaceRange(sid, 2, 4, "[折叠]", "tool-fold");
            surfaceStore.restoreRange(sid, 2, 4, "restore");
            assertEquals(2, events.size());
            assertEquals("REPLACE_RANGE", events.get(0).payload().get("op"));
            assertEquals("restore", events.get(1).payload().get("reason"));
        } finally {
            dispose.run();
        }
    }
}
