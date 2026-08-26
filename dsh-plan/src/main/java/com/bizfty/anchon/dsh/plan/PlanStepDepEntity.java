package com.bizfty.anchon.dsh.plan;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.util.Objects;

/**
 * DAG 依赖边（表 {@code plan_step_dep}）— 复合主键 (step_id, depends_on_step_id)。
 * <p>
 * 语义：step_id 依赖 depends_on_step_id（后者完成后前者才能开始）。
 * 环检测由 {@link PlanService} 在建计划/加边时执行。
 */
@Entity
@Table(name = "plan_step_dep")
@IdClass(PlanStepDepEntity.DepId.class)
public class PlanStepDepEntity {

    @Id
    @Column(name = "step_id", length = 64, nullable = false)
    private String stepId;

    @Id
    @Column(name = "depends_on_step_id", length = 64, nullable = false)
    private String dependsOnStepId;

    protected PlanStepDepEntity() {
    }

    public PlanStepDepEntity(String stepId, String dependsOnStepId) {
        this.stepId = stepId;
        this.dependsOnStepId = dependsOnStepId;
    }

    public String getStepId() {
        return stepId;
    }

    public String getDependsOnStepId() {
        return dependsOnStepId;
    }

    /** 复合主键类。 */
    public static class DepId implements Serializable {
        private String stepId;
        private String dependsOnStepId;

        public DepId() {
        }

        public DepId(String stepId, String dependsOnStepId) {
            this.stepId = stepId;
            this.dependsOnStepId = dependsOnStepId;
        }

        public String getStepId() {
            return stepId;
        }

        public void setStepId(String stepId) {
            this.stepId = stepId;
        }

        public String getDependsOnStepId() {
            return dependsOnStepId;
        }

        public void setDependsOnStepId(String dependsOnStepId) {
            this.dependsOnStepId = dependsOnStepId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof DepId other)) {
                return false;
            }
            return Objects.equals(stepId, other.stepId)
                    && Objects.equals(dependsOnStepId, other.dependsOnStepId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(stepId, dependsOnStepId);
        }
    }
}
