package com.bizfty.anchon.dsh.plan;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * DAG 计划头（表 {@code plan}）— 每会话至多一个活动计划（对应官方 plan 的持久化形态升级）。
 * <p>
 * status：active / completed / abandoned。计划步骤与依赖分别在
 * {@link PlanStepEntity}（plan_step）与 {@link PlanStepDepEntity}（plan_step_dep）。
 */
@Entity
@Table(name = "plan", indexes = {
        @Index(name = "idx_plan_session", columnList = "session_id")
})
public class PlanEntity {

    @Id
    @Column(length = 64, nullable = false)
    private String id;

    @Column(name = "session_id", length = 128, nullable = false)
    private String sessionId;

    @Column(length = 512, nullable = false)
    private String title;

    @Column(length = 32, nullable = false)
    private String status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PlanEntity() {
    }

    public PlanEntity(String id, String sessionId, String title, String status,
                      Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.sessionId = sessionId;
        this.title = title;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public String getId() {
        return id;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
