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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M2-5 §6.4 读一致性对照：{@code read-model=fact-replay} 下 {@link SessionService#listMessages}
 * 从 fact 真相只读重放，语义与 {@code table}（投影缓存）读结果一致（除消息 id 为派生 id 外
 * 逐字段相同、顺序相同、行数相同）；pruned 标记、TOOL 行、SYSTEM 行均保持。
 * 用例 3：未知 read-model 值 fail-safe 回退 table（含 pruned 修剪流程不受影响）。
 */
@SpringBootTest(properties = "dsh.session.read-model=fact-replay",
        classes = SessionReadModelSwitchTest.FactReplayConfig.class)
class SessionReadModelSwitchTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EnableJpaRepositories(basePackageClasses = SessionFactRepository.class)
    @EntityScan(basePackageClasses = SessionFactEntity.class)
    @Import({SessionService.class, SessionFactStore.class, SessionReadModel.class})
    static class FactReplayConfig {
    }

    @Autowired
    private SessionService sessionService;
    @Autowired
    private SessionFactStore store;
    @Autowired
    private SessionMessageRepository messageRepository;
    @Autowired
    private SessionFactRepository factRepository;
    @Autowired
    private SessionReadModel readModel;

    private SessionId sid;

    @BeforeEach
    void buildMixedSession() {
        assertTrue(readModel.isFactReplay(), "测试上下文应启用 fact-replay");
        Session s = sessionService.createSession("rm", "deepseek-chat", "/workspace");
        sid = s.id();
        store.append(sid, MessageRole.SYSTEM, "技能种子", null, null, null, null);
        store.append(sid, MessageRole.USER, "q1", null, null, null, null);
        store.append(sid, MessageRole.ASSISTANT, "{\"calls\":[1]}", null, null, "[{\"id\":\"c1\"}]", null);
        store.append(sid, MessageRole.TOOL, "{\"r\":1}", "c1", "fs", null, null);
    }

    @Test
    void factReplayReadMatchesProjectionTableReadSemantically() {
        // 修剪 TOOL 行后再对照：pruned 在两读路径一致
        String toolMsgId = messageRepository.findBySessionIdOrderBySeqAsc(sid.value()).get(3).getId();
        sessionService.markToolResultPruned(sid, toolMsgId);

        // fact-replay 读（经开关，读 fact）
        List<SessionMessage> factView = sessionService.listMessages(sid);
        // table 读：投影仓库行 → domain（基准真值）
        List<SessionMessage> tableView = messageRepository.findBySessionIdOrderBySeqAsc(sid.value())
                .stream().map(SessionMessageEntity::toDomain).toList();

        assertEquals(4, factView.size());
        assertEquals(factRepository.countBySessionId(sid.value()), factView.size());
        assertEquals(tableView.size(), factView.size(), "fact 行数 == 投影行数");
        assertEquals(tableView.size(), messageRepository.countBySessionId(sid.value()));

        for (int i = 0; i < tableView.size(); i++) {
            SessionMessage t = tableView.get(i);
            SessionMessage f = factView.get(i);
            assertEquals(t.seq(), f.seq(), "seq 顺序一致 @" + i);
            assertEquals(t.role(), f.role(), "role 一致 @" + i);
            assertEquals(t.content(), f.content(), "content 一致 @" + i);
            assertEquals(t.toolCallId(), f.toolCallId(), "toolCallId 一致 @" + i);
            assertEquals(t.toolName(), f.toolName(), "toolName 一致 @" + i);
            assertEquals(t.toolCallsJson(), f.toolCallsJson(), "toolCallsJson 一致 @" + i);
            assertEquals(t.pruned(), f.pruned(), "pruned 一致 @" + i);
            assertTrue(t.createdAt().equals(f.createdAt()) || Math.abs(
                            t.createdAt().toEpochMilli() - f.createdAt().toEpochMilli()) < 2_000,
                    "createdAt 一致 @" + i);
        }
        assertEquals(MessageRole.TOOL, factView.get(3).role());
        assertTrue(factView.get(3).pruned(), "TOOL 行 pruned 应保持");
        assertEquals(MessageRole.SYSTEM, factView.get(0).role(), "SYSTEM 行应在 fact 中保留");
    }
}

/**
 * 未知 read-model 值 → fail-safe 回退 table（读投影缓存，消息 id 与投影行一致）。
 */
@SpringBootTest(properties = "dsh.session.read-model=typo-mode",
        classes = SessionReadModelSwitchTest.FactReplayConfig.class)
class SessionReadModelUnknownValueTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EnableJpaRepositories(basePackageClasses = SessionFactRepository.class)
    @EntityScan(basePackageClasses = SessionFactEntity.class)
    @Import({SessionService.class, SessionFactStore.class, SessionReadModel.class})
    static class FallbackConfig {
    }

    @Autowired
    private SessionService sessionService;
    @Autowired
    private SessionReadModel readModel;
    @Autowired
    private SessionMessageRepository messageRepository;

    @Test
    void unknownModeFallsBackToTableRead() {
        org.junit.jupiter.api.Assertions.assertFalse(readModel.isFactReplay());
        Session s = sessionService.createSession("rm-bad", "deepseek-chat", "/workspace");
        sessionService.append(s.id(), MessageRole.USER, "hi", null, null, null);
        List<SessionMessage> messages = sessionService.listMessages(s.id());
        List<SessionMessage> table = messageRepository.findBySessionIdOrderBySeqAsc(s.id().value())
                .stream().map(SessionMessageEntity::toDomain).toList();
        assertEquals(table.size(), messages.size());
        assertEquals(table.get(0).id(), messages.get(0).id(), "回退 table 读应返回投影行原 id");
        assertEquals("hi", messages.get(0).content());
    }
}
