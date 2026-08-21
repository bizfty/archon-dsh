package com.example.dsh.session;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * 会话消息查询仓储 — 关键词检索（对应 DSH session-query 的检索面）。
 */
public interface SessionMessageQueryRepository extends JpaRepository<SessionMessageEntity, String> {

    @Query("SELECT m FROM SessionMessageEntity m WHERE LOWER(m.content) LIKE LOWER(CONCAT('%', :keyword, '%')) "
            + "ORDER BY m.createdAt DESC")
    List<SessionMessageEntity> searchByKeyword(@Param("keyword") String keyword, Pageable pageable);
}
