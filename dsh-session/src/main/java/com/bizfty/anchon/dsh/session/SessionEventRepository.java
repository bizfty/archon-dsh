package com.bizfty.anchon.dsh.session;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * anchon_session_event 数据访问。
 */
public interface SessionEventRepository extends JpaRepository<SessionEventEntity, String> {

    List<SessionEventEntity> findBySessionIdOrderBySeqAsc(String sessionId);

    /** 某会话指定事件类型最近 N 条（seq 倒序，供 durable series 恢复等定向查询）。 */
    List<SessionEventEntity> findTop5BySessionIdAndEventTypeOrderBySeqDesc(String sessionId, String eventType);
}
