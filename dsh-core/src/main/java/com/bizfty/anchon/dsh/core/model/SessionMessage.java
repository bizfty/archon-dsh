package com.bizfty.anchon.dsh.core.model;

import java.time.Instant;

/**
 * 会话消息 — 持久化单元（会话日志的表面消息行）。
 * <p>
 * 对应 DSH session 事件溯源中可派生到模型历史的 surface 事件
 * （user/message、assistant/message、tool/result）。
 *
 * @param id           消息 id
 * @param sessionId    所属会话
 * @param role         角色
 * @param content      文本内容（USER/ASSISTANT 的正文；TOOL 的 JSON 结果）
 * @param toolCallId   工具调用 id（TOOL 消息必需；ASSISTANT 携带调用时也可填）
 * @param toolName     工具名（TOOL 消息必需）
 * @param toolCallsJson ASSISTANT 消息携带的工具调用列表 JSON（可为 null）
 * @param seq          会话内单调序号（投影顺序）
 * @param createdAt    创建时间
 * @param pruned       工具结果是否已 durable 修剪（P2-②）：true 时投影层对该 TOOL 行输出
 *                     {@code 截断视图}，原文 content 保留在行内（日志无损）；非 TOOL / 未修剪恒 false
 */
public record SessionMessage(
        String id,
        SessionId sessionId,
        MessageRole role,
        String content,
        String toolCallId,
        String toolName,
        String toolCallsJson,
        long seq,
        Instant createdAt,
        boolean pruned) {

    /** 兼容构造（P2 前调用点）：未修剪消息。 */
    public SessionMessage(String id, SessionId sessionId, MessageRole role, String content,
                          String toolCallId, String toolName, String toolCallsJson,
                          long seq, Instant createdAt) {
        this(id, sessionId, role, content, toolCallId, toolName, toolCallsJson, seq, createdAt, false);
    }

    public boolean hasToolCalls() {
        return toolCallsJson != null && !toolCallsJson.isBlank();
    }
}
