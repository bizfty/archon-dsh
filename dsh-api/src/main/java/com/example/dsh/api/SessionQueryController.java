package com.example.dsh.api;

import com.example.dsh.session.SessionQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 会话检索端点（对应 DSH session-query 的模型侧工具面；当前为 REST 检索）。
 */
@RestController
@RequestMapping("/api/sessions/query")
public class SessionQueryController {

    private final SessionQueryService queryService;

    public SessionQueryController(SessionQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping
    public List<SessionQueryService.SearchHit> search(
            @RequestParam String keyword,
            @RequestParam(defaultValue = "20") int limit) {
        return queryService.search(keyword, limit);
    }
}
