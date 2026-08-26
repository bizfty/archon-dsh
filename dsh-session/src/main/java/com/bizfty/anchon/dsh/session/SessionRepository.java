package com.bizfty.anchon.dsh.session;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SessionRepository extends JpaRepository<SessionEntity, String> {

    /**
     * 按工作目录查询会话。cwd 与工作区是 1 对多（一个工作区可有多个会话），
     * 故返回 {@link List} 而非 {@link Optional}——若声明为 Optional，
     * 当某 cwd 下存在 2+ 会话时 Spring Data 会抛
     * "Query did not return a unique result"。
     */
    List<SessionEntity> findByCwd(String cwd);
}
