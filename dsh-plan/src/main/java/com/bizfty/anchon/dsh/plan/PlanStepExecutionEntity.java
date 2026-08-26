package com.bizfty.anchon.dsh.plan;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 计划步骤 ↔ 工具调用 关联表（表 {@code plan_step_execution}）。
 * <p>
 * 记录"某个计划步骤执行期间发生的工具调用"，用于点击已执行节点时
 * 展示该步骤的操作过程。并行执行下每个调用独立一行，天然准确。
 * plan_step_id 可空（自由执行/无计划时不写）。
 */
@Entity
@Table(name = "plan_step_execution", indexes = {
        @Index(name = "idx_pse_step", columnList = "plan_step_id"),
        @Index(name = "idx_pse_session", columnList = "session_id")
})
public class PlanStepExecutionEntity {

    @Id
    @Column(length = 64, nullable = false)
    private String id;

    @Column(name = "plan_id", length = 64, nullable = false)
    private String planId;

    @Column(name = "plan_step_id", length = 64, nullable = false)
    private String planStepId;

    @Column(name = "session_id", length = 128, nullable = false)
    private String sessionId;

    @Column(name = "tool_name", length = 64, nullable = false)
    private String toolName;

    @Column(name = "args_summary", length = 512)
    private String argsSummary;

    @Column(name = "tool_call_id", length = 128)
    private String toolCallId;

    @Column(name = "status", length = 32)
    private String status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected PlanStepExecutionEntity() {
    }

    public PlanStepExecutionEntity(String id, String planId, String planStepId, String sessionId,
                                   String toolName, String argsSummary, String toolCallId,
                                   String status, Instant createdAt) {
        this.id = id;
        this.planId = planId;
        this.planStepId = planStepId;
        this.sessionId = sessionId;
        this.toolName = toolName;
        this.argsSummary = argsSummary;
        this.toolCallId = toolCallId;
        this.status = status;
        this.createdAt = createdAt;
    }

    public String getId() {
        return id;
    }

    public String getPlanId() {
        return planId;
    }

    public String getPlanStepId() {
        return planStepId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getToolName() {
        return toolName;
    }

    public String getArgsSummary() {
        return argsSummary;
    }

    public String getToolCallId() {
        return toolCallId;
    }

    public String getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
