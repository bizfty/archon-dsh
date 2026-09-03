package com.bizfty.anchon.dsh.session;

import com.bizfty.anchon.dsh.core.event.SessionEvent;
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
 * 中断 turn 修复测试（C-③）：崩溃留下的 open TURN_START 被追加 interrupted 封口；
 * 已闭合 turn / 无悬空会话不受影响；重复启动幂等。
 */
@SpringBootTest(classes = InterruptedTurnRepairerTest.TestConfig.class)
class InterruptedTurnRepairerTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EnableJpaRepositories(basePackageClasses = SessionEventRepository.class)
    @EntityScan(basePackageClasses = SessionEventEntity.class)
    @Import({JsonUtils.class, InterruptedTurnRepairer.class})
    static class TestConfig {
    }

    @Autowired
    private SessionEventRepository repository;

    @Autowired
    private InterruptedTurnRepairer repairer;

    private final JsonUtils json = new JsonUtils();

    private void seed(String sessionId, SessionEventType type, long seq, Map<String, Object> payload) {
        SessionEvent event = SessionEvent.of(SessionId.of(sessionId), type, seq, payload);
        repository.save(SessionEventEntity.from(event, json.toJson(payload)));
    }

    @Test
    void openTurnGetsInterruptedCloserAndRepairIsIdempotent() {
        seed("s_open", SessionEventType.TURN_START, 1, Map.of("executionId", "run-1", "model", "deepseek-chat"));
        seed("s_open", SessionEventType.STEP_START, 2, Map.of("step", 1));
        seed("s_open", SessionEventType.TOOL_CALL, 3, Map.of("tool", "bash"));

        int first = repairer.repairAll();
        assertEquals(1, first);

        List<SessionEventEntity> events = repository.findBySessionIdOrderBySeqAsc("s_open");
        assertEquals(4, events.size());
        SessionEventEntity closer = events.get(events.size() - 1);
        assertEquals("TURN_END", closer.getEventType());
        assertTrue(closer.getPayloadJson().contains("\"finish\":\"interrupted\""), closer.getPayloadJson());
        assertTrue(closer.getPayloadJson().contains("\"steps\":1"));
        assertTrue(closer.getPayloadJson().contains("\"tool_calls\":1"));
        assertTrue(closer.getPayloadJson().contains("\"executionId\":\"run-1\""));
        assertEquals(4, closer.getSeq());

        // 幂等：第二次不再追加
        assertEquals(0, repairer.repairAll());
        assertEquals(4, repository.findBySessionIdOrderBySeqAsc("s_open").size());
    }

    @Test
    void closedSessionsAreUntouched() {
        seed("s_stop", SessionEventType.TURN_START, 1, Map.of("executionId", "run-a"));
        seed("s_stop", SessionEventType.TURN_END, 2, Map.of("finish", "stop"));
        seed("s_err", SessionEventType.TURN_START, 1, Map.of("executionId", "run-b"));
        seed("s_err", SessionEventType.TURN_ERROR, 2, Map.of("error_type", "internal"));

        assertEquals(0, repairer.repairAll());
        assertEquals(2, repository.findBySessionIdOrderBySeqAsc("s_stop").size());
        assertEquals(2, repository.findBySessionIdOrderBySeqAsc("s_err").size());
    }

    @Test
    void onlyLastOpenTurnIsClosedWithOwnCounters() {
        // 已闭合 turn1 + 悬空 turn2
        seed("s_multi", SessionEventType.TURN_START, 1, Map.of("executionId", "run-1"));
        seed("s_multi", SessionEventType.TURN_END, 2, Map.of("finish", "stop"));
        seed("s_multi", SessionEventType.TURN_START, 3, Map.of("executionId", "run-2"));
        seed("s_multi", SessionEventType.STEP_START, 4, Map.of("step", 1));
        seed("s_multi", SessionEventType.MODEL_REQUEST, 5, Map.of("model", "deepseek-chat"));

        assertEquals(1, repairer.repairAll());

        List<SessionEventEntity> events = repository.findBySessionIdOrderBySeqAsc("s_multi");
        assertEquals(6, events.size());
        SessionEventEntity closer = events.get(events.size() - 1);
        assertEquals("TURN_END", closer.getEventType());
        assertTrue(closer.getPayloadJson().contains("\"executionId\":\"run-2\""));
        assertTrue(closer.getPayloadJson().contains("\"steps\":1"));
        assertEquals(6, closer.getSeq());
    }
}
