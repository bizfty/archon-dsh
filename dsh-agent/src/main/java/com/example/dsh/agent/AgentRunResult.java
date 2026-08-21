package com.example.dsh.agent;

import com.example.dsh.core.model.SessionId;

/**
 * Agent 运行结果。
 */
public record AgentRunResult(
        String content,
        SessionId sessionId,
        int steps,
        int toolCalls) {

    public static AgentRunResult text(String content, SessionId sessionId, int steps, int toolCalls) {
        return new AgentRunResult(content == null ? "" : content, sessionId, steps, toolCalls);
    }
}
