package com.bizfty.anchon.dsh.api.openai;

import java.util.List;

/**
 * OpenAI 兼容流式 Chunk（SSE data: 行）。
 */
public record ChatCompletionChunk(
        String id,
        long created,
        String model,
        List<ChunkChoice> choices) {

    public record ChunkChoice(int index, Delta delta, String finishReason) {
    }

    public record Delta(String role, String content) {
    }
}
