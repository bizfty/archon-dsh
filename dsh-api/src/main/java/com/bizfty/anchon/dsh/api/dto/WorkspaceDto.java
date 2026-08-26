package com.bizfty.anchon.dsh.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 工作区视图（对齐官方 WorkspaceView：工作区 + 其下会话 id 列表）。
 */
public record WorkspaceDto(
        String id,
        String path,
        String title,
        List<String> sessionIds,
        Instant createdAt,
        Instant updatedAt) {
}
