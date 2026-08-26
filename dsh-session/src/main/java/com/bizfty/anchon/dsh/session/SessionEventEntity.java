package com.bizfty.anchon.dsh.session;

import com.bizfty.anchon.dsh.core.event.SessionEvent;
import com.bizfty.anchon.dsh.core.model.SessionId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 会话执行事件日志实体（表 {@code anchon_session_event}）。
 * <p>
 * 持久化 turn 级执行过程信息：TURN_START / STEP_START / MODEL_REQUEST /
 * TOOL_CALL / TOOL_RESULT / ASSISTANT_MESSAGE / TURN_END / TURN_ERROR 等
 * （默认不含逐 token 的 ASSISTANT_TOKEN，避免海量行；如需完整回放可开开关）。
 * payload 为事件载荷的 JSON 序列化。
 */
@Entity
@Table(name = "anchon_session_event", indexes = {
        @Index(name = "idx_anchon_event_session_seq", columnList = "sessionId,seq")
})
public class SessionEventEntity {

    @Id
    @Column(length = 64)
    private String id;

    @Column(length = 64, nullable = false)
    private String sessionId;

    private long seq;

    @Column(length = 32, nullable = false)
    private String eventType;

    @Column(length = 64)
    private String executionId;

    @Column(columnDefinition = "TEXT")
    private String payloadJson;

    private Instant createdAt;

    protected SessionEventEntity() {
    }

    public static SessionEventEntity from(SessionEvent event, String payloadJson) {
        SessionEventEntity e = new SessionEventEntity();
        e.id = "evt-" + event.seq() + "-" + event.sessionId().value().hashCode();
        e.sessionId = event.sessionId().value();
        e.seq = event.seq();
        e.eventType = event.type().name();
        e.executionId = event.string("executionId");
        e.payloadJson = payloadJson;
        e.createdAt = event.timestamp();
        return e;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public long getSeq() {
        return seq;
    }

    public void setSeq(long seq) {
        this.seq = seq;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public String getExecutionId() {
        return executionId;
    }

    public void setExecutionId(String executionId) {
        this.executionId = executionId;
    }

    public String getPayloadJson() {
        return payloadJson;
    }

    public void setPayloadJson(String payloadJson) {
        this.payloadJson = payloadJson;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    /** 领域视图（查询端点用）。 */
    public static MapView toView(SessionEventEntity e) {
        return new MapView(e.id, SessionId.of(e.sessionId), e.seq, e.eventType, e.executionId, e.payloadJson, e.createdAt);
    }

    /** 事件日志行视图（避免直接暴露实体）。 */
    public record MapView(String id, SessionId sessionId, long seq, String eventType,
                          String executionId, String payloadJson, Instant createdAt) {
    }
}
