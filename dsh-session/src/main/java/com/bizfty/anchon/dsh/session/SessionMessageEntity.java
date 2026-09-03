package com.bizfty.anchon.dsh.session;

import com.bizfty.anchon.dsh.core.model.MessageRole;
import com.bizfty.anchon.dsh.core.model.SessionId;
import com.bizfty.anchon.dsh.core.model.SessionMessage;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 会话消息持久化实体 — 对应会话日志的 surface 消息行。
 */
@Entity
@Table(name = "anchon_session_message", indexes = {
        @Index(name = "idx_anchon_msg_session_seq", columnList = "sessionId,seq")
})
public class SessionMessageEntity {

    @Id
    @Column(length = 64)
    private String id;

    @Column(length = 64, nullable = false)
    private String sessionId;

    @Column(length = 16, nullable = false)
    private String role;

    @Column(columnDefinition = "TEXT")
    private String content;

    @Column(length = 64)
    private String toolCallId;

    @Column(length = 128)
    private String toolName;

    @Column(columnDefinition = "TEXT")
    private String toolCallsJson;

    private long seq;

    /** 工具结果 durable 修剪标记（P2-②）：true 时投影层截断视图，原文 content 保留。 */
    private boolean pruned;

    private Instant createdAt;

    protected SessionMessageEntity() {
    }

    public static SessionMessageEntity from(SessionMessage message) {
        SessionMessageEntity e = new SessionMessageEntity();
        e.id = message.id();
        e.sessionId = message.sessionId().value();
        e.role = message.role().name();
        e.content = message.content();
        e.toolCallId = message.toolCallId();
        e.toolName = message.toolName();
        e.toolCallsJson = message.toolCallsJson();
        e.seq = message.seq();
        e.createdAt = message.createdAt();
        e.pruned = message.pruned();
        return e;
    }

    public SessionMessage toDomain() {
        return new SessionMessage(id, SessionId.of(sessionId), MessageRole.valueOf(role), content,
                toolCallId, toolName, toolCallsJson, seq, createdAt, pruned);
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

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getToolCallId() {
        return toolCallId;
    }

    public void setToolCallId(String toolCallId) {
        this.toolCallId = toolCallId;
    }

    public String getToolName() {
        return toolName;
    }

    public void setToolName(String toolName) {
        this.toolName = toolName;
    }

    public String getToolCallsJson() {
        return toolCallsJson;
    }

    public void setToolCallsJson(String toolCallsJson) {
        this.toolCallsJson = toolCallsJson;
    }

    public long getSeq() {
        return seq;
    }

    public boolean isPruned() {
        return pruned;
    }

    public void setPruned(boolean pruned) {
        this.pruned = pruned;
    }

    public void setSeq(long seq) {
        this.seq = seq;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
