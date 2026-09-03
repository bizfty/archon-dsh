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

    /** 某会话指定事件类型最新一条（M4-5：RestoreAgentRunner 读每会话最新 AGENT_PHASE）。 */
    List<SessionEventEntity> findTop1BySessionIdAndEventTypeOrderBySeqDesc(String sessionId, String eventType);

    /** 出现过指定事件类型的所有会话 id（M4-5：启动恢复扫描 AGENT_PHASE 会话集合）。 */
    @org.springframework.data.jpa.repository.Query(
            "select distinct e.sessionId from SessionEventEntity e where e.eventType = ?1")
    List<String> findSessionIdsByEventType(String eventType);
}
