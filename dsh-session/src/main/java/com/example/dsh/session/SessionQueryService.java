package com.example.dsh.session;

import com.example.dsh.core.model.MessageRole;
import com.example.dsh.core.model.SessionId;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * 会话检索服务 — 跨会话关键词搜索消息（对应 DSH session-query；独立于压缩）。
 */
@Service
public class SessionQueryService {

    private final SessionMessageQueryRepository queryRepository;

    public SessionQueryService(SessionMessageQueryRepository queryRepository) {
        this.queryRepository = queryRepository;
    }

    /** 搜索命中。 */
    public record SearchHit(
            String sessionId, String role, String content,
            String toolName, Instant createdAt) {
    }

    /**
     * 按关键词搜索消息（不区分大小写，按时间倒序）。
     */
    public List<SearchHit> search(String keyword, int limit) {
        if (keyword == null || keyword.isBlank()) {
            return List.of();
        }
        int max = Math.min(Math.max(1, limit), 100);
        return queryRepository.searchByKeyword(keyword.trim(), PageRequest.of(0, max))
                .stream()
                .map(e -> new SearchHit(
                        e.getSessionId(),
                        e.getRole(),
                        e.getContent(),
                        e.getToolName(),
                        e.getCreatedAt()))
                .toList();
    }
}
