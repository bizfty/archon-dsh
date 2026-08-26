package com.bizfty.anchon.dsh.plan;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

/**
 * plan_step_dep 表数据访问。
 */
public interface PlanStepDepRepository extends JpaRepository<PlanStepDepEntity, PlanStepDepEntity.DepId> {

    List<PlanStepDepEntity> findByStepIdIn(Collection<String> stepIds);
}
