package com.bizfty.anchon.dsh.session;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * anchon_session_fact（事实事件，append-only）数据访问。
 */
public interface SessionFactRepository extends JpaRepository<SessionFactEntity, String> {

    List<SessionFactEntity> findBySessionIdOrderBySeqAsc(String sessionId);

    long countBySessionId(String sessionId);

    /** 某会话指定 seq 的事实（UK(session_id,seq) 保证至多一条）。 */
    Optional<SessionFactEntity> findBySessionIdAndSeq(String sessionId, long seq);
}
