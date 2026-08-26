package com.bizfty.anchon.dsh.interaction;

import com.bizfty.anchon.dsh.tool.ToolContext;

import java.time.Instant;

/**
 * 审批请求 — 一次工具调用的审批上下文。
 */
public record ApprovalRequest(
        String id,
        String toolName,
        String reason,
        ToolContext context,
        Instant createdAt) {

    public static ApprovalRequest of(String id, String toolName, String reason, ToolContext context) {
        return new ApprovalRequest(id, toolName, reason, context, Instant.now());
    }
}
