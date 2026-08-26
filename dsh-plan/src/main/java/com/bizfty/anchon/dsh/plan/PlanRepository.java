package com.bizfty.anchon.dsh.plan;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * plan 表数据访问。
 */
public interface PlanRepository extends JpaRepository<PlanEntity, String> {

    /** 某会话最近的活动计划（按 updatedAt 倒序第一个）。 */
    Optional<PlanEntity> findFirstBySessionIdOrderByUpdatedAtDesc(String sessionId);

    /** 某会话的全部计划（按创建时间倒序）。 */
    List<PlanEntity> findBySessionIdOrderByCreatedAtDesc(String sessionId);
}
