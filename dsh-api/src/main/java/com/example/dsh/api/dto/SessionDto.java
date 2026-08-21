package com.example.dsh.api.dto;

import java.time.Instant;

public record SessionDto(
        String id,
        String title,
        String model,
        String cwd,
        String status,
        Instant createdAt,
        Instant updatedAt) {
}
