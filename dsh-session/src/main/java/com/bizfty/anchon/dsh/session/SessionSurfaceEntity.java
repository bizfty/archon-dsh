package com.bizfty.anchon.dsh.session;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * 会话表面指令实体（表 {@code anchon_session_surface}，M8 surfaceOp 落地）。
 * <p>
 * 职责（docs/design-surface-op.md §4.1）：在不可变消息真相 {@code anchon_session_fact} 之上，
 * 记录"会话可见面（模型侧看到什么）的显式演进"——REPLACE_HEAD / REPLACE_RANGE / APPEND_VIEW
 * 指令，append-only 累积；{@code uk_anchon_surface_session_gen} 保证 (sessionId,gen) 单调唯一。
 * 不触碰 fact/消息投影行（surface 只作用于模型可见视图，不删行；fact 仍为唯一消息真相）。
 */
@Entity
@Table(name = "anchon_session_surface", indexes = {
        @Index(name = "idx_anchon_surface_session_range", columnList = "sessionId,rangeFrom,rangeTo")
}, uniqueConstraints = {
        @UniqueConstraint(name = "uk_anchon_surface_session_gen", columnNames = {"sessionId", "gen"})
})
public class SessionSurfaceEntity {

    @Id
    @Column(length = 64)
    private String id;

    @Column(length = 64, nullable = false)
    private String sessionId;

    /** 可见面代数：会话内单调（1..N），每次表面操作 +1。 */
    private long gen;

    /** REPLACE_HEAD | REPLACE_RANGE | APPEND_VIEW。 */
    @Column(length = 16, nullable = false)
    private String op;

    /** op 作用的事实 seq 起始（含）。 */
    private long rangeFrom;

    /** 事实 seq 结束（含）；null = 到当前末尾。 */
    private Long rangeTo;

    /** REPLACE 时的摘要/折叠文本（USER 角色代表行）；null = 纯遮蔽。 */
    @Column(columnDefinition = "TEXT")
    private String replacement;

    /** 可选元数据 JSON：如 {"reason":"compaction|manual|tool-fold","restores":true}。 */
    @Column(columnDefinition = "TEXT")
    private String metaJson;

    private Instant createdAt;

    protected SessionSurfaceEntity() {
    }

    public static SessionSurfaceEntity of(String id, String sessionId, long gen, SurfaceOp op,
                                          long rangeFrom, Long rangeTo, String replacement,
                                          String metaJson, Instant createdAt) {
        SessionSurfaceEntity e = new SessionSurfaceEntity();
        e.id = id;
        e.sessionId = sessionId;
        e.gen = gen;
        e.op = op.name();
        e.rangeFrom = rangeFrom;
        e.rangeTo = rangeTo;
        e.replacement = replacement;
        e.metaJson = metaJson;
        e.createdAt = createdAt;
        return e;
    }

    public SurfaceInstruction toInstruction() {
        return new SurfaceInstruction(gen, SurfaceOp.valueOf(op), rangeFrom, rangeTo, replacement, metaJson);
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

    public long getGen() {
        return gen;
    }

    public void setGen(long gen) {
        this.gen = gen;
    }

    public String getOp() {
        return op;
    }

    public void setOp(String op) {
        this.op = op;
    }

    public long getRangeFrom() {
        return rangeFrom;
    }

    public void setRangeFrom(long rangeFrom) {
        this.rangeFrom = rangeFrom;
    }

    public Long getRangeTo() {
        return rangeTo;
    }

    public void setRangeTo(Long rangeTo) {
        this.rangeTo = rangeTo;
    }

    public String getReplacement() {
        return replacement;
    }

    public void setReplacement(String replacement) {
        this.replacement = replacement;
    }

    public String getMetaJson() {
        return metaJson;
    }

    public void setMetaJson(String metaJson) {
        this.metaJson = metaJson;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
