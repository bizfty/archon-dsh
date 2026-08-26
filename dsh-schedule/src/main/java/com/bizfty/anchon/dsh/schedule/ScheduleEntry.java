package com.bizfty.anchon.dsh.schedule;

import com.bizfty.anchon.dsh.core.model.SessionId;

import java.time.Instant;

/**
 * 提醒条目（对应 DSH schedule 的会话内提醒状态）。
 */
public record ScheduleEntry(
        String id,
        SessionId sessionId,
        String message,
        Instant dueAt,
        Status status,
        Instant createdAt) {

    public enum Status {
        PENDING,
        FIRED,
        CANCELED
    }

    public ScheduleEntry withStatus(Status status) {
        return new ScheduleEntry(id, sessionId, message, dueAt, status, createdAt);
    }
}
