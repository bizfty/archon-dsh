package com.bizfty.anchon.dsh.plan;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * plan_step 表数据访问。
 */
public interface PlanStepRepository extends JpaRepository<PlanStepEntity, String> {

    List<PlanStepEntity> findByPlanIdOrderBySeqAsc(String planId);

    List<PlanStepEntity> findByPlanIdAndStatusIn(String planId, List<String> statuses);
}
