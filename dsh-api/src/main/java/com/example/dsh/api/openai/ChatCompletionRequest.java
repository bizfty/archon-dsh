package com.example.dsh.api.openai;

import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容 Chat Completions 请求（对应 DSH api/gateway 的 OpenAI 兼容面）。
 */
public record ChatCompletionRequest(
        String model,
        List<ChatMessage> messages,
        Boolean stream,
        Map<String, Object> metadata) {

    public record ChatMessage(String role, Object content) {
    }
}
