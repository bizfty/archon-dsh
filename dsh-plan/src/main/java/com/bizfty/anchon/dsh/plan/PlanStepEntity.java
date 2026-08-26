package com.bizfty.anchon.dsh.plan;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * DAG 计划步骤（表 {@code plan_step}）— 一个计划节点的可执行单元。
 * <p>
 * status：pending / in_progress / completed / cancelled / skipped / failed。
 * kind：task（实现步骤，默认）/ proposal / spec / design / doc（工件类型）。
 * reviewed：doc 类（非 task）步骤须 reviewed=true 才进入 nextSteps（审阅门）；
 * task 类步骤不受审阅门约束。
 * seq 为创建时的稳定序号（展示与拓扑排序参考；真正依赖关系在 plan_step_dep）。
 */
@Entity
@Table(name = "plan_step", indexes = {
        @Index(name = "idx_plan_step_plan", columnList = "plan_id")
})
public class PlanStepEntity {

    @Id
    @Column(length = 64, nullable = false)
    private String id;

    @Column(name = "plan_id", length = 64, nullable = false)
    private String planId;

    @Column(length = 512, nullable = false)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(length = 32, nullable = false)
    private String status;

    /** 是否必须完成（todo 语义：必做项）。false = 可取消/跳过而不阻塞计划完成。 */
    @Column(nullable = false)
    private boolean required = true;

    /** 工件类型：task / proposal / spec / design / doc。task = 实现步骤。 */
    @Column(length = 16, nullable = false)
    private String kind = "task";

    /** 是否已审阅批准：doc 类步骤须 reviewed=true 才可执行（审阅门）。 */
    @Column(nullable = false)
    private boolean reviewed = false;

    @Column(nullable = false)
    private int seq;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PlanStepEntity() {
    }

    public PlanStepEntity(String id, String planId, String title, String description,
                          String status, int seq, Instant createdAt, Instant updatedAt) {
        this(id, planId, title, description, status, true, "task", false, seq, createdAt, updatedAt);
    }

    public PlanStepEntity(String id, String planId, String title, String description,
                          String status, boolean required, int seq,
                          Instant createdAt, Instant updatedAt) {
        this(id, planId, title, description, status, required, "task", false, seq, createdAt, updatedAt);
    }

    public PlanStepEntity(String id, String planId, String title, String description,
                          String status, boolean required, String kind, boolean reviewed, int seq,
                          Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.planId = planId;
        this.title = title;
        this.description = description;
        this.status = status;
        this.required = required;
        this.kind = kind == null || kind.isBlank() ? "task" : kind;
        this.reviewed = reviewed;
        this.seq = seq;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public String getId() {
        return id;
    }

    public String getPlanId() {
        return planId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public boolean isRequired() {
        return required;
    }

    public void setRequired(boolean required) {
        this.required = required;
    }

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public boolean isReviewed() {
        return reviewed;
    }

    public void setReviewed(boolean reviewed) {
        this.reviewed = reviewed;
    }

    public int getSeq() {
        return seq;
    }

    public void setSeq(int seq) {
        this.seq = seq;
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
