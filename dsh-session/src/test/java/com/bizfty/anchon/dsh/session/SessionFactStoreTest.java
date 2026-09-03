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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SessionFactStore（M2 事件为真）集成测试：同事务写 fact+投影+会话行（原子性）、
 * SYSTEM 角色、markPruned 双表一致、并发 append fail-fast 无脏序（design §6.2/§4.2）。
 */
@SpringBootTest(classes = SessionFactStoreTest.TestConfig.class)
class SessionFactStoreTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EnableJpaRepositories(basePackageClasses = SessionFactRepository.class)
    @EntityScan(basePackageClasses = SessionFactEntity.class)
    @Import({SessionService.class, SessionFactStore.class})
    static class TestConfig {
    }

    @Autowired
    private SessionFactStore store;
    @Autowired
    private SessionService sessionService;
    @Autowired
    private SessionFactRepository factRepository;
    @Autowired
    private SessionMessageRepository messageRepository;

    private SessionId sid;

    @BeforeEach
    void newSession() {
        Session s = sessionService.createSession("fact-store", "deepseek-chat", "/workspace");
        sid = s.id();
    }

    @Test
    void appendWritesFactAndProjectionAtomicallyWithSystemRole() {
        SessionMessage m1 = store.append(sid, MessageRole.USER, "你好", null, null, null, null);
        assertEquals(1, m1.seq());
        SessionMessage m2 = store.append(sid, MessageRole.SYSTEM, "技能种子", null, null, null, "{\"compacted\":true}");
        assertEquals(2, m2.seq());
        store.append(sid, MessageRole.ASSISTANT, "回复", null, null, null, null);

        assertEquals(3, factRepository.countBySessionId(sid.value()));
        assertEquals(3, messageRepository.countBySessionId(sid.value()));

        List<SessionFactEntity> facts = factRepository.findBySessionIdOrderBySeqAsc(sid.value());
        List<SessionMessageEntity> msgs = messageRepository.findBySessionIdOrderBySeqAsc(sid.value());
        assertEquals(List.of("USER", "SYSTEM", "ASSISTANT"),
                facts.stream().map(SessionFactEntity::getRole).toList());
        assertEquals("技能种子", facts.get(1).getContent());
        assertEquals("{\"compacted\":true}", facts.get(1).getMetaJson());
        assertFalse(facts.get(1).isPruned());
        // 投影与 fact 逐条同构（seq/role/content）
        for (int i = 0; i < 3; i++) {
            assertEquals(facts.get(i).getSeq(), msgs.get(i).getSeq());
            assertEquals(facts.get(i).getRole(), msgs.get(i).getRole());
            assertEquals(facts.get(i).getContent(), msgs.get(i).getContent());
        }
    }

    @Test
    void markPrunedMarksFactAndProjectionKeepingContent() {
        SessionMessage tool = store.append(sid, MessageRole.TOOL, "{\"result\":\"x\"}",
                "call_1", "fs_read", null, null);

        int affected = store.markPruned(sid, tool.id());
        assertEquals(1, affected);

        SessionMessageEntity msg = messageRepository.findById(tool.id()).orElseThrow();
        assertTrue(msg.isPruned());
        assertEquals("{\"result\":\"x\"}", msg.getContent()); // 原文保留

        SessionFactEntity fact = factRepository.findBySessionIdAndSeq(sid.value(), 1).orElseThrow();
        assertTrue(fact.isPruned());
        assertEquals("{\"result\":\"x\"}", fact.getContent());

        // 不属于本会话/不存在 → 0 且不动
        assertEquals(0, store.markPruned(sid, "no-such-id"));
    }

    @Test
    void concurrentAppendFailFastNoDirtySeq() throws Exception {
        int threads = 6;
        int perThread = 5;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<List<Object>>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            final int base = t;
            futures.add(pool.submit(() -> {
                barrier.await();
                List<Object> outcomes = new ArrayList<>();
                for (int i = 0; i < perThread; i++) {
                    try {
                        SessionMessage m = store.append(sid, MessageRole.USER, "u-" + base + "-" + i,
                                null, null, null, null);
                        outcomes.add(m.seq());
                    } catch (SessionService.SessionConcurrentModificationException e) {
                        outcomes.add("conflict"); // 预期的 fail-fast
                    }
                }
                return outcomes;
            }));
        }
        List<Object> all = new ArrayList<>();
        for (Future<List<Object>> f : futures) {
            all.addAll(f.get());
        }
        pool.shutdown();

        long conflicts = all.stream().filter("conflict"::equals).count();
        List<Long> seqs = all.stream().filter(Long.class::isInstance).map(Long.class::cast)
                .sorted().toList();
        int successes = seqs.size();
        assertTrue(successes >= 1, "至少一次成功追加");
        assertTrue(seqs.size() + conflicts == (long) threads * perThread, "成功+冲突=总请求数");

        // 红线：不得静默重复 seq/幻读 —— 成功者 seq 必须连续 1..S 且无重复
        assertEquals(IntStream.rangeClosed(1, successes).asLongStream().boxed().toList(), seqs);
        assertEquals(successes, messageRepository.countBySessionId(sid.value()));
        assertEquals(successes, factRepository.countBySessionId(sid.value()));

        List<SessionMessage> msgs = sessionService.listMessages(sid);
        assertEquals(successes, msgs.size());
        assertEquals(msgs.get(msgs.size() - 1).seq(), successes);
    }
}
