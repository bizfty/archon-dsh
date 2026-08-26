package com.bizfty.anchon.dsh.api.dto;

import java.time.Instant;
import java.util.Map;

public record MessageDto(
        String id,
        String role,
        String content,
        String toolName,
        String toolCallId,
        Instant createdAt,
        Map<String, Object> metadata) {

    public static MessageDto simple(String id, String role, String content) {
        return new MessageDto(id, role, content, null, null, Instant.now(), null);
    }
}
