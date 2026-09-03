package com.bizfty.anchon.dsh.session;

import com.bizfty.anchon.dsh.core.model.MessageRole;
import com.bizfty.anchon.dsh.core.model.Session;
import com.bizfty.anchon.dsh.core.model.SessionId;
import com.bizfty.anchon.dsh.core.model.SessionMessage;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会话持久化测试（H2 内存库）：创建/追加/投影顺序/会话不存在。
 * <p>
 * 说明：Boot 4 将测试切片拆到独立构件（spring-boot-starter-data-jpa-test），
 * 本地仓库只有 4.0.0-RC2，故用 @SpringBootTest + 最小 JPA 配置替代 @DataJpaTest。
 */
@SpringBootTest(classes = SessionServiceTest.TestConfig.class)
class SessionServiceTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EnableJpaRepositories(basePackageClasses = SessionRepository.class)
    @EntityScan(basePackageClasses = SessionEntity.class)
    @Import(SessionService.class)
    static class TestConfig {
    }

    @org.springframework.beans.factory.annotation.Autowired
    private SessionService sessionService;

    @Test
    void createAndAppendMessagesWithMonotonicSeq() {
        Session session = sessionService.createSession("测试", "deepseek-chat", "/workspace");
        SessionId id = session.id();

        sessionService.append(id, MessageRole.USER, "你好", null, null, null);
        sessionService.append(id, MessageRole.ASSISTANT, "你好！", null, null, null);

        List<SessionMessage> messages = sessionService.listMessages(id);
        assertEquals(2, messages.size());
        assertEquals(1L, messages.get(0).seq());
        assertEquals(2L, messages.get(1).seq());
        assertEquals("你好", messages.get(0).content());
    }

    @Test
    void toolMessagesRoundTrip() {
        Session session = sessionService.createSession(null, null, null);
        SessionId id = session.id();
        sessionService.append(id, MessageRole.ASSISTANT, "", null, null,
                "[{\"id\":\"call_1\",\"type\":\"function\",\"name\":\"bash\",\"arguments\":\"{\\\"command\\\":\\\"pwd\\\"}\"}]");
        sessionService.append(id, MessageRole.TOOL, "{\"success\":true}", "call_1", "bash", null);

        List<SessionMessage> messages = sessionService.listMessages(id);
        assertEquals(2, messages.size());
        assertEquals(true, messages.get(0).hasToolCalls());
        assertEquals("call_1", messages.get(1).toolCallId());
        assertEquals("bash", messages.get(1).toolName());
    }

    @Test
    void getSessionReflectsUpdates() {
        Session session = sessionService.createSession("t", "m1", null);
        sessionService.updateModel(session.id(), "m2");
        sessionService.updateTitle(session.id(), "t2");
        Session reloaded = sessionService.getSession(session.id());
        assertEquals("m2", reloaded.model());
        assertEquals("t2", reloaded.title());
    }

    @Test
    void missingSessionThrows() {
        assertThrows(SessionService.SessionNotFoundException.class,
                () -> sessionService.getSession(SessionId.of("sess_nope")));
    }

    @Test
    void persistedSessionSurvivesReload() {
        Session created = sessionService.createSession("持久化", "deepseek-chat", "/ws");
        Session loaded = sessionService.getSession(created.id());
        assertNotNull(loaded);
        assertEquals(created.id(), loaded.id());
        assertEquals("/ws", loaded.cwd());
    }

    @Test
    void markToolResultPrunedFlagsRowAndKeepsOriginalContent() {
        Session session = sessionService.createSession(null, null, null);
        SessionId id = session.id();
        String huge = "A".repeat(10_000);
        sessionService.append(id, MessageRole.ASSISTANT, "", null, null,
                "[{\"id\":\"call_1\",\"type\":\"function\",\"name\":\"bash\",\"arguments\":\"{}\"}]");
        SessionMessage tool = sessionService.append(id, MessageRole.TOOL, huge, "call_1", "bash", null);

        assertEquals(1, sessionService.markToolResultPruned(id, tool.id()));

        SessionMessage reloaded = sessionService.listMessages(id).stream()
                .filter(m -> m.id().equals(tool.id())).findFirst().orElseThrow();
        assertTrue(reloaded.pruned(), "行应标记 pruned");
        assertEquals(huge, reloaded.content(), "原文 content 必须保留（日志无损）");
    }

    @Test
    void markToolResultPrunedForeignOrMissingMessageIsNoOp() {
        Session s1 = sessionService.createSession(null, null, null);
        Session s2 = sessionService.createSession(null, null, null);
        SessionMessage tool = sessionService.append(s1.id(), MessageRole.TOOL, "x".repeat(5000), "call_1", "bash", null);

        assertEquals(0, sessionService.markToolResultPruned(s1.id(), "msg_ghost"));
        assertEquals(0, sessionService.markToolResultPruned(s2.id(), tool.id()), "跨会话不应置位");
        assertEquals(0, sessionService.markToolResultPruned(SessionId.of("sess_ghost"), tool.id()));
    }

    // ---- P2-③ 写安全：会话行乐观锁 ----

    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.transaction.PlatformTransactionManager txManager;
    @org.springframework.beans.factory.annotation.Autowired
    private SessionRepository sessionRepository;

    @Test
    void optimisticLockConflictFailsSecondWriterAndKeepsFirst() {
        Session session = sessionService.createSession("t", "m", null);
        String sid = session.id().value();
        org.springframework.transaction.support.TransactionTemplate tx =
                new org.springframework.transaction.support.TransactionTemplate(txManager);

        // 两个持久化上下文（模拟多实例）都读到 v0
        SessionEntity w1 = tx.execute(s -> sessionRepository.findById(sid).orElseThrow());
        SessionEntity w2 = tx.execute(s -> sessionRepository.findById(sid).orElseThrow());

        tx.executeWithoutResult(s -> {
            w1.setTitle("writer-1");
            w1.setUpdatedAt(java.time.Instant.now());
            sessionRepository.saveAndFlush(w1); // v0 → v1
        });

        assertThrows(org.springframework.dao.OptimisticLockingFailureException.class, () ->
                tx.executeWithoutResult(s -> {
                    w2.setTitle("writer-2");
                    w2.setUpdatedAt(java.time.Instant.now());
                    sessionRepository.saveAndFlush(w2); // 基于过期 v0 → 冲突
                }));

        SessionEntity reloaded = sessionRepository.findById(sid).orElseThrow();
        assertEquals("writer-1", reloaded.getTitle(), "先提交者生效，后提交者不得静默覆盖");
        assertEquals(1L, reloaded.getVersion(), "version 应递增到 1");
    }

    // ---- P2-④ CreateSessionOptions seeding ----

    @Test
    void createSessionWithSeedsPersistsSeedMessagesWithMonotonicSeq() {
        Session session = sessionService.createSession(new SessionService.CreateSessionOptions(
                "种子会话", "deepseek-chat", "/ws",
                List.of(new SessionService.SeedMessage(MessageRole.SYSTEM, "你是助手"),
                        new SessionService.SeedMessage(MessageRole.USER, "第一问"))));

        List<SessionMessage> messages = sessionService.listMessages(session.id());
        assertEquals(2, messages.size());
        assertEquals(1L, messages.get(0).seq());
        assertEquals(2L, messages.get(1).seq());
        assertEquals(MessageRole.SYSTEM, messages.get(0).role());
        assertEquals("第一问", messages.get(1).content());
        assertEquals("种子会话", sessionService.getSession(session.id()).title());
    }

    @Test
    void createSessionOptionsWithoutSeedsEqualsPlainCreate() {
        Session seeded = sessionService.createSession(SessionService.CreateSessionOptions.of("t", "m", null));
        Session plain = sessionService.createSession("t2", "m", null);
        assertEquals("t", seeded.title());
        assertEquals(0, sessionService.listMessages(seeded.id()).size());
        assertEquals(0, sessionService.listMessages(plain.id()).size());
    }

    @Test
    void seedMessageRejectsNullRoleOrContent() {
        assertThrows(IllegalArgumentException.class,
                () -> new SessionService.SeedMessage(null, "x"));
        assertThrows(IllegalArgumentException.class,
                () -> new SessionService.SeedMessage(MessageRole.USER, null));
    }
}
