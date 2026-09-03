package com.bizfty.anchon.dsh.session;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface SessionMessageRepository extends JpaRepository<SessionMessageEntity, String> {

    List<SessionMessageEntity> findBySessionIdOrderBySeqAsc(String sessionId);

    long countBySessionId(String sessionId);

    /** durable 修剪标记（P2-②）：仅置 pruned=true，不改 content（日志无损）。返回受影响行数。 */
    @Modifying
    @Query("update SessionMessageEntity e set e.pruned = true where e.id = :id and e.sessionId = :sessionId")
    int markPruned(@Param("id") String id, @Param("sessionId") String sessionId);
}
