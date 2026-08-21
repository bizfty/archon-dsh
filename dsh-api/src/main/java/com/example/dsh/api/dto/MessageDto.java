package com.example.dsh.api.dto;

import java.time.Instant;
import java.util.Map;

public record MessageDto(
        String id,
        String role,
        String content,
        Instant createdAt,
        Map<String, Object> metadata) {

    public static MessageDto simple(String id, String role, String content) {
        return new MessageDto(id, role, content, Instant.now(), null);
    }
}
