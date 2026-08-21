package com.example.dsh.feedback;

import com.example.dsh.core.model.SessionId;

import java.time.Instant;

/**
 * 消息反馈 — 不可变记录（对应 DSH message-feedback：评分/备注 sidecar，
 * 不进模型上下文）。
 */
public record FeedbackRecord(
        String id,
        SessionId sessionId,
        String messageId,
        Integer rating,
        String comment,
        Instant createdAt) {

    public static FeedbackRecord of(SessionId sessionId, String messageId, Integer rating, String comment) {
        return new FeedbackRecord("fb_" + java.util.UUID.randomUUID().toString().substring(0, 8),
                sessionId, messageId, rating, comment, Instant.now());
    }
}
