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
}
