package com.bizfty.anchon.dsh.session;

import com.bizfty.anchon.dsh.core.model.MessageRole;
import com.bizfty.anchon.dsh.core.model.Session;
import com.bizfty.anchon.dsh.core.model.SessionId;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SessionFactBackfillService 集成测试（M2 存量回填）：legacy 消息行（无 fact）→
 * 快照回填 fact、幂等重跑、pruned 保留、自检无失配会话。
 */
@SpringBootTest(classes = SessionFactBackfillServiceTest.TestConfig.class)
class SessionFactBackfillServiceTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EnableJpaRepositories(basePackageClasses = SessionFactRepository.class)
    @EntityScan(basePackageClasses = SessionFactEntity.class)
    @Import({SessionService.class, SessionFactBackfillService.class, SessionFactStore.class})
    static class TestConfig {
    }

    @Autowired
    private SessionFactBackfillService backfill;
    @Autowired
    private SessionService sessionService;
    @Autowired
    private SessionFactRepository factRepository;
    @Autowired
    private SessionMessageRepository messageRepository;

    @Test
    void backfillSnapshotsLegacyMessagesIdempotentlyKeepingPruned() {
        // 用"改造前"状态造 legacy 数据：只插消息行（无 fact，绕过 m2-3 后的 SessionService.append 委托）
        Session s1 = sessionService.createSession("legacy-1", "deepseek-chat", "/workspace");
        SessionId id1 = s1.id();
        messageRepository.save(SessionMessageEntity.from(new com.bizfty.anchon.dsh.core.model.SessionMessage(
                "msg_legacy_1", id1, MessageRole.USER, "q1", null, null, null, 1, java.time.Instant.now())));
        messageRepository.save(SessionMessageEntity.from(new com.bizfty.anchon.dsh.core.model.SessionMessage(
                "msg_legacy_2", id1, MessageRole.ASSISTANT, "a1", null, null, null, 2, java.time.Instant.now())));
        SessionMessageEntity toolLegacy = SessionMessageEntity.from(
                new com.bizfty.anchon.dsh.core.model.SessionMessage(
                        "msg_legacy_3", id1, MessageRole.TOOL, "{\"r\":1}", "call1", "fs", null,
                        3, java.time.Instant.now()));
        toolLegacy.setPruned(true); // legacy pruned 标记（直接置行）
        messageRepository.save(toolLegacy);

        Session s2 = sessionService.createSession("legacy-2", "deepseek-chat", "/workspace");
        SessionId id2 = s2.id();
        messageRepository.save(SessionMessageEntity.from(new com.bizfty.anchon.dsh.core.model.SessionMessage(
                "msg_legacy_s1", id2, MessageRole.SYSTEM, "seed", null, null, null, 1, java.time.Instant.now())));

        // 回填前：无 fact
        assertEquals(0, factRepository.countBySessionId(id1.value()));
        assertEquals(0, factRepository.countBySessionId(id2.value()));

        int inserted = backfill.backfill();
        assertEquals(4, inserted);

        // fact 自 seq=1 连续、内容/pruned 与消息行一致
        List<SessionFactEntity> facts1 = factRepository.findBySessionIdOrderBySeqAsc(id1.value());
        assertEquals(3, facts1.size());
        assertEquals(1, facts1.get(0).getSeq());
        assertEquals(3, facts1.get(2).getSeq());
        assertEquals("TOOL", facts1.get(2).getRole());
        assertTrue(facts1.get(2).isPruned(), "回填应保留 pruned 标记");
        assertEquals("{\"r\":1}", facts1.get(2).getContent());
        assertEquals("SYSTEM", factRepository.findBySessionIdAndSeq(id2.value(), 1).orElseThrow().getRole());

        // 幂等重跑：不重复插入
        assertEquals(0, backfill.backfill());
        assertEquals(3, factRepository.countBySessionId(id1.value()));
        assertEquals(1, factRepository.countBySessionId(id2.value()));

        // 自检：无失配会话
        assertTrue(backfill.findInconsistentSessions().isEmpty());
    }
}
