package com.bizfty.anchon.dsh.subagent;

import com.bizfty.anchon.dsh.core.model.SessionId;

import java.time.Instant;

/**
 * 子代理句柄 — 一个子代理 = 一个持久子会话（continuable：可继续 send_message）。
 */
public record SubagentHandle(
        String id,
        SessionId sessionId,
        int delegationDepth,
        SubagentStatus status,
        String lastContent,
        Instant createdAt) {

    public SubagentHandle withStatus(SubagentStatus status) {
        return new SubagentHandle(id, sessionId, delegationDepth, status, lastContent, createdAt);
    }

    public SubagentHandle withResult(SubagentStatus status, String content) {
        return new SubagentHandle(id, sessionId, delegationDepth, status, content, createdAt);
    }
}
