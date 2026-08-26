package com.bizfty.anchon.dsh.session;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SessionMessageRepository extends JpaRepository<SessionMessageEntity, String> {

    List<SessionMessageEntity> findBySessionIdOrderBySeqAsc(String sessionId);

    long countBySessionId(String sessionId);
}
