package com.bizfty.anchon.dsh.session;

import com.bizfty.anchon.dsh.core.model.MessageRole;
import com.bizfty.anchon.dsh.core.model.Session;
import com.bizfty.anchon.dsh.core.model.SessionId;
import com.bizfty.anchon.dsh.core.model.SessionMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SessionFactReplayer + SessionProjectionVerifier（M2 §4.4/§6.3）：投影半写（fact 已写、
 * 投影缺尾/缺行）→ verifier 检出 → 按会话重建自愈；幂等重跑。
 */
@SpringBootTest(classes = SessionFactReplayVerifierTest.TestConfig.class)
class SessionFactReplayVerifierTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EnableJpaRepositories(basePackageClasses = SessionFactRepository.class)
    @EntityScan(basePackageClasses = SessionFactEntity.class)
    @Import({SessionService.class, SessionFactStore.class, SessionFactReplayer.class,
            SessionProjectionVerifier.class})
    static class TestConfig {
    }

    @Autowired
    private SessionFactStore store;
    @Autowired
    private SessionService sessionService;
    @Autowired
    private SessionFactReplayer replayer;
    @Autowired
    private SessionProjectionVerifier verifier;
    @Autowired
    private SessionMessageRepository messageRepository;
    @Autowired
    private SessionFactRepository factRepository;

    private SessionId sid;

    @BeforeEach
    void buildSessionWithThreeFacts() {
        Session s = sessionService.createSession("replay", "deepseek-chat", "/workspace");
        sid = s.id();
        store.append(sid, MessageRole.USER, "q1", null, null, null, null);
        store.append(sid, MessageRole.ASSISTANT, "a1", null, null, null, null);
        store.append(sid, MessageRole.TOOL, "{\"r\":1}", "call1", "fs", null, null);
    }

    @Test
    void verifierSeesNoDiffWhenProjectionIntact() {
        assertTrue(verifier.diff(sid).isEmpty());
        assertFalse(verifier.repairIfMismatch(sid));
    }

    @Test
    void crashRecoveryRepairsMissingProjectionTail() {
        // 模拟崩溃半写：fact 已写、投影缺最后 2 条（append 事务只提交了 fact 部分）
        List<SessionMessageEntity> tail = messageRepository.findBySessionIdOrderBySeqAsc(sid.value());
        messageRepository.deleteById(tail.get(2).getId());
        messageRepository.deleteById(tail.get(1).getId());

        List<String> reasons = verifier.diff(sid);
        assertFalse(reasons.isEmpty(), "半写应被检出");
        assertTrue(reasons.stream().anyMatch(r -> r.startsWith("投影缺 seq=")));

        assertTrue(verifier.repairIfMismatch(sid), "检出后应重建自愈");

        List<SessionMessage> messages = sessionService.listMessages(sid);
        assertEquals(3, messages.size());
        assertEquals(1, messages.get(0).seq());
        assertEquals(3, messages.get(2).seq());
        assertEquals("{\"r\":1}", messages.get(2).content());
        assertEquals(3, messageRepository.countBySessionId(sid.value()));
        assertTrue(verifier.diff(sid).isEmpty());
    }

    @Test
    void replayIsIdempotentAndPreservesPruned() {
        // 标记 TOOL 行为 pruned 后重建：标记应保留
        String toolMsgId = messageRepository.findBySessionIdOrderBySeqAsc(sid.value()).get(2).getId();
        store.markPruned(sid, toolMsgId);

        int built = replayer.rebuild(sid);
        assertEquals(3, built);
        int builtAgain = replayer.rebuild(sid);
        assertEquals(3, builtAgain);
        assertEquals(3, messageRepository.countBySessionId(sid.value()));
        assertTrue(messageRepository.findBySessionIdOrderBySeqAsc(sid.value()).get(2).isPruned(),
                "重建应保留 pruned 标记");
        assertTrue(factRepository.findBySessionIdOrderBySeqAsc(sid.value()).get(2).isPruned());
        assertTrue(verifier.diff(sid).isEmpty());
    }
}
