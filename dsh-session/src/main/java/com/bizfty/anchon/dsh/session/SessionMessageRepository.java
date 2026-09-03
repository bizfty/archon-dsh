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

    /** 全部有消息投影的会话 id（供投影校验器启动抽查遍历）。 */
    @Query("select distinct e.sessionId from SessionMessageEntity e")
    List<String> findAllSessionIds();

    /** 幂等重建：删除某会话全部投影行（投影为可丢弃缓存，真相在 fact）。 */
    long deleteBySessionId(String sessionId);
}
