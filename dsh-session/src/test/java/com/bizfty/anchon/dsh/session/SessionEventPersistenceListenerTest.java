package com.bizfty.anchon.dsh.session;

import com.bizfty.anchon.dsh.core.event.SessionEventBus;
import com.bizfty.anchon.dsh.core.event.SessionEventType;
import com.bizfty.anchon.dsh.core.model.SessionId;
import com.bizfty.anchon.dsh.util.JsonUtils;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 执行过程事件持久化测试：turn 级事件落库、ASSISTANT_TOKEN 默认跳过、开关可开启。
 * Boot 4 无 @DataJpaTest，按项目惯例用 @SpringBootTest + 最小 JPA 配置。
 */
@SpringBootTest(classes = SessionEventPersistenceListenerTest.TestConfig.class)
class SessionEventPersistenceListenerTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EnableJpaRepositories(basePackageClasses = SessionEventRepository.class)
    @EntityScan(basePackageClasses = SessionEventEntity.class)
    @Import(JsonUtils.class)
    static class TestConfig {
    }

    @Autowired
    private SessionEventRepository repository;

    private SessionEventBus bus(boolean includeTokens) {
        SessionEventBus eventBus = new SessionEventBus();
        new SessionEventPersistenceListener(eventBus, repository, new JsonUtils(), includeTokens);
        return eventBus;
    }

    @Test
    void turnEventsArePersisted() {
        SessionEventBus bus = bus(false);
        SessionId id = SessionId.of("sess_evt");

        bus.publish(id, SessionEventType.TURN_START, Map.of("executionId", "run-1", "model", "deepseek-chat"));
        bus.publish(id, SessionEventType.TOOL_RESULT, Map.of("tool", "bash", "success", true));

        List<SessionEventEntity> events = repository.findBySessionIdOrderBySeqAsc("sess_evt");
        assertEquals(2, events.size());
        assertEquals("TURN_START", events.get(0).getEventType());
        assertEquals("TOOL_RESULT", events.get(1).getEventType());
        assertEquals("run-1", events.get(0).getExecutionId());
        assertTrue(events.get(1).getPayloadJson().contains("bash"));
    }

    @Test
    void assistantTokenSkippedByDefault() {
        SessionEventBus bus = bus(false);
        SessionId id = SessionId.of("sess_evt2");

        bus.publish(id, SessionEventType.ASSISTANT_TOKEN, Map.of("content", "hello"));
        bus.publish(id, SessionEventType.ASSISTANT_MESSAGE, Map.of("content", "hello"));

        List<SessionEventEntity> events = repository.findBySessionIdOrderBySeqAsc("sess_evt2");
        assertEquals(1, events.size(), "默认应跳过 ASSISTANT_TOKEN，只存 ASSISTANT_MESSAGE");
        assertEquals("ASSISTANT_MESSAGE", events.get(0).getEventType());
    }

    @Test
    void includeTokensPersistsEveryToken() {
        SessionEventBus bus = bus(true);
        SessionId id = SessionId.of("sess_evt3");

        bus.publish(id, SessionEventType.ASSISTANT_TOKEN, Map.of("content", "a"));
        bus.publish(id, SessionEventType.ASSISTANT_TOKEN, Map.of("content", "b"));

        assertEquals(2, repository.findBySessionIdOrderBySeqAsc("sess_evt3").size());
    }

    @Test
    void turnErrorIsPersistedWithMessage() {
        SessionEventBus bus = bus(false);
        SessionId id = SessionId.of("sess_evt4");

        bus.publish(id, SessionEventType.TURN_ERROR, Map.of("message", "超过最大步数上限: 25"));

        List<SessionEventEntity> events = repository.findBySessionIdOrderBySeqAsc("sess_evt4");
        assertEquals(1, events.size());
        assertEquals("TURN_ERROR", events.get(0).getEventType());
        assertTrue(events.get(0).getPayloadJson().contains("超过最大步数上限"));
    }

    @Test
    void unrelatedEventTypesAreSkipped() {
        SessionEventBus bus = bus(false);
        SessionId id = SessionId.of("sess_evt5");

        bus.publish(id, SessionEventType.FEEDBACK, Map.of("score", 5));
        bus.publish(id, SessionEventType.SESSION_CREATED, Map.of());

        assertTrue(repository.findBySessionIdOrderBySeqAsc("sess_evt5").isEmpty());
    }
}
