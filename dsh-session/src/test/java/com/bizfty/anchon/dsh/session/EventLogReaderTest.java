package com.bizfty.anchon.dsh.session;

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

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * EventLogReader 测试（P2-① durable series 恢复查询 + D.3 format-refusal 容错精神）。
 * Boot 4 无 @DataJpaTest，按项目惯例用 @SpringBootTest + 最小 JPA 配置。
 */
@SpringBootTest(classes = EventLogReaderTest.TestConfig.class)
class EventLogReaderTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EnableJpaRepositories(basePackageClasses = SessionEventRepository.class)
    @EntityScan(basePackageClasses = SessionEventEntity.class)
    @Import(JsonUtils.class)
    static class TestConfig {
    }

    @Autowired
    private SessionEventRepository repository;

    private final JsonUtils jsonUtils = new JsonUtils();

    private EventLogReader reader() {
        return new EventLogReader(repository, jsonUtils);
    }

    /** 直接构造一条 MODEL_REQUEST 事件行（payload 自定）。 */
    private void saveModelRequest(String sessionId, long seq, String callSite, String headerFingerprint) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", "deepseek-chat");
        payload.put("callSite", callSite);
        payload.put("messages", List.of());
        payload.put("options", Map.of());
        if (headerFingerprint != null) {
            payload.put("headerFingerprint", headerFingerprint);
        }
        save(sessionId, seq, SessionEventType.MODEL_REQUEST.name(), payload);
    }

    private void save(String sessionId, long seq, String eventType, Map<String, Object> payload) {
        SessionEventEntity e = new SessionEventEntity();
        e.setId("evt-" + seq + "-" + sessionId.hashCode());
        e.setSessionId(sessionId);
        e.setSeq(seq);
        e.setEventType(eventType);
        e.setPayloadJson(jsonUtils.toJson(payload));
        e.setCreatedAt(Instant.now());
        repository.save(e);
    }

    @Test
    void returnsLastAgentTurnHeaderFingerprint() {
        SessionId id = SessionId.of("sess_dur");
        saveModelRequest("sess_dur", 1, "agent_turn", "fp-1");
        saveModelRequest("sess_dur", 2, "agent_turn", "fp-2");

        assertEquals(Optional.of("fp-2"), reader().lastAgentTurnHeaderFingerprint(id));
    }

    @Test
    void skipsAuxiliaryCallSitesAndFindsOlderAgentTurn() {
        SessionId id = SessionId.of("sess_dur2");
        saveModelRequest("sess_dur2", 1, "agent_turn", "fp-1");
        // 辅助调用点夹在中间（无 headerFingerprint）→ 应跳过并回看更早 agent_turn
        saveModelRequest("sess_dur2", 2, "session_title", null);

        assertEquals(Optional.of("fp-1"), reader().lastAgentTurnHeaderFingerprint(id));
    }

    @Test
    void noEventsOrOldDataReturnsEmpty() {
        assertEquals(Optional.empty(), reader().lastAgentTurnHeaderFingerprint(SessionId.of("sess_empty")));

        SessionId id = SessionId.of("sess_old");
        saveModelRequest("sess_old", 1, "agent_turn", null); // P2 前旧数据：无指纹
        assertEquals(Optional.empty(), reader().lastAgentTurnHeaderFingerprint(id));
    }

    @Test
    void unparsableRowIsSkippedWithWarningNotFailure() {
        SessionId id = SessionId.of("sess_bad");
        // 坏行（非法 JSON）在最前（seq 高）→ 跳过；好行照常返回
        save("sess_bad", 2, SessionEventType.MODEL_REQUEST.name(), null); // payload null → skip
        saveModelRequest("sess_bad", 1, "agent_turn", "fp-ok");

        assertEquals(Optional.of("fp-ok"), reader().lastAgentTurnHeaderFingerprint(id));
    }

    @Test
    void auxiliaryOnlySessionReturnsEmpty() {
        SessionId id = SessionId.of("sess_aux");
        saveModelRequest("sess_aux", 1, "session_title", null);
        saveModelRequest("sess_aux", 2, "compaction", null);
        assertEquals(Optional.empty(), reader().lastAgentTurnHeaderFingerprint(id));
        assertTrue(repository.findBySessionIdOrderBySeqAsc("sess_aux").size() == 2);
    }

    // ---- D.3 format-refusal 精神：通用容错读取 ----

    @Test
    void readEventsSkipsBadRowsAndKeepsGoodOnesInSeqOrder() {
        SessionId id = SessionId.of("sess_read");
        saveModelRequest("sess_read", 1, "agent_turn", "fp-1");
        save("sess_read", 2, SessionEventType.MODEL_REQUEST.name(), null); // 坏行（payload null）
        Map<String, Object> good = new LinkedHashMap<>();
        good.put("model", "deepseek-chat");
        good.put("callSite", "agent_turn");
        save("sess_read", 3, SessionEventType.MODEL_REQUEST.name(), good);

        List<SessionEventEntity> events = reader().readEvents(id);
        assertEquals(2, events.size(), "坏行应被跳过，好行按 seq 升序保留");
        assertEquals(1L, events.get(0).getSeq());
        assertEquals(3L, events.get(1).getSeq());
    }

    @Test
    void readEventsEmptySessionReturnsEmpty() {
        assertTrue(reader().readEvents(SessionId.of("sess_none")).isEmpty());
    }
}
