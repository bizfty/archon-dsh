package com.bizfty.anchon.dsh.api.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * 聊天请求（自有 REST API）。
 */
public record ChatRequest(
        @NotBlank String message,
        String model,
        String agentId,
        List<String> skillIds) {
}
