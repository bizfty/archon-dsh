package com.bizfty.anchon.dsh.plan;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * plan_step_execution 表数据访问。
 */
public interface PlanStepExecutionRepository extends JpaRepository<PlanStepExecutionEntity, String> {

    List<PlanStepExecutionEntity> findByPlanStepIdOrderByCreatedAtAsc(String planStepId);
}
