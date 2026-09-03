package com.bizfty.anchon.dsh.session;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * anchon_session_surface（表面指令，append-only）数据访问。
 */
public interface SessionSurfaceRepository extends JpaRepository<SessionSurfaceEntity, String> {

    /** 按会话取全部表面指令，gen 升序（当前可见面 = 依序应用遮蔽区间）。 */
    List<SessionSurfaceEntity> findBySessionIdOrderByGenAsc(String sessionId);

    /** 某会话表面指令条数（写侧用于分配下一 gen：count + 1，会话行乐观锁 gate 后稳定）。 */
    long countBySessionId(String sessionId);

    /** 会话销毁/清理时级联删除表面指令（可选，与 fact 表清理策略一致）。 */
    void deleteBySessionId(String sessionId);
}
