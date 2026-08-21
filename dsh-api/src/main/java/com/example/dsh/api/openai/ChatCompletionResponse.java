package com.example.dsh.api.openai;

import java.util.List;

/**
 * OpenAI 兼容 Chat Completions 响应。
 */
public record ChatCompletionResponse(
        String id,
        long created,
        String model,
        List<Choice> choices,
        Usage usage) {

    public record Choice(int index, ChatMessage message, String finishReason) {
    }

    public record ChatMessage(String role, String content) {
    }

    public record Usage(int promptTokens, int completionTokens, int totalTokens) {
    }
}
