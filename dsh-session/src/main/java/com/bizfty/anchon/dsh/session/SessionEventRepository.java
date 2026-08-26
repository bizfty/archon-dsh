package com.bizfty.anchon.dsh.session;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * anchon_session_event 数据访问。
 */
public interface SessionEventRepository extends JpaRepository<SessionEventEntity, String> {

    List<SessionEventEntity> findBySessionIdOrderBySeqAsc(String sessionId);
}
