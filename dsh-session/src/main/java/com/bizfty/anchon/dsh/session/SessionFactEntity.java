package com.bizfty.anchon.dsh.session;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * 会话消息事实事件实体（表 {@code anchon_session_fact}，M2 事件为真）。
 * <p>
 * 职责（design-event-sourced-session.md §4.1）：会话内逐条消息追加的 <b>append-only 真相</b>，
 * 是消息序的唯一事实源；{@code anchon_session_message} 在 M2 后降级为其同事务物化的投影缓存。
 * 与审计流 {@code anchon_session_event} 职责分离（事件 payload 有损，不参与消息重建）。
 * <p>
 * 列与 {@code anchon_session_message} 同构 + {@code meta_json}（如 compacted 来源标记）；
 * 唯一约束 {@code uk_anchon_fact_session_seq} 为 seq 并发兜底（第二道防线，
 * 第一道防线为会话行乐观锁，见 §4.2）。
 */
@Entity
@Table(name = "anchon_session_fact", indexes = {
        @Index(name = "idx_anchon_fact_session", columnList = "sessionId")
}, uniqueConstraints = {
        @UniqueConstraint(name = "uk_anchon_fact_session_seq", columnNames = {"sessionId", "seq"})
})
public class SessionFactEntity {

    @Id
    @Column(length = 64)
    private String id;

    @Column(length = 64, nullable = false)
    private String sessionId;

    private long seq;

    /** USER | SYSTEM | ASSISTANT | TOOL（SYSTEM：SkillController 技能种子行等）。 */
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

    /** durable 修剪标记：true 时投影层截断视图，原文 content 保留（与消息行语义一致）。 */
    private boolean pruned;

    /** 可选元数据 JSON：如 {"compacted":true} 摘要行来源标记等。 */
    @Column(columnDefinition = "TEXT")
    private String metaJson;

    private Instant createdAt;

    protected SessionFactEntity() {
    }

    public static SessionFactEntity of(String id, String sessionId, long seq, String role, String content,
                                       String toolCallId, String toolName, String toolCallsJson,
                                       String metaJson, Instant createdAt) {
        SessionFactEntity e = new SessionFactEntity();
        e.id = id;
        e.sessionId = sessionId;
        e.seq = seq;
        e.role = role;
        e.content = content;
        e.toolCallId = toolCallId;
        e.toolName = toolName;
        e.toolCallsJson = toolCallsJson;
        e.pruned = false;
        e.metaJson = metaJson;
        e.createdAt = createdAt;
        return e;
    }

    public String getId() {
        return id;
    }

    public String getSessionId() {
        return sessionId;
    }

    public long getSeq() {
        return seq;
    }

    public void setSeq(long seq) {
        this.seq = seq;
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

    public String getToolCallId() {
        return toolCallId;
    }

    public String getToolName() {
        return toolName;
    }

    public String getToolCallsJson() {
        return toolCallsJson;
    }

    public boolean isPruned() {
        return pruned;
    }

    public void setPruned(boolean pruned) {
        this.pruned = pruned;
    }

    public String getMetaJson() {
        return metaJson;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
