package com.bizfty.anchon.dsh.core.event;

import com.bizfty.anchon.dsh.core.model.SessionId;

import java.time.Instant;
import java.util.Map;

/**
 * 会话事件 — 不可变事件值。
 * <p>
 * 对应 DSH session/event；payload 为全量状态或增量事实，
 * 由监听器（持久化、投影、UI 推送）各自消费。
 */
public record SessionEvent(
        SessionId sessionId,
        SessionEventType type,
        long seq,
        Instant timestamp,
        Map<String, Object> payload) {

    public static SessionEvent of(SessionId sessionId, SessionEventType type, long seq, Map<String, Object> payload) {
        return new SessionEvent(sessionId, type, seq, Instant.now(), payload);
    }

    /** 取 payload 中的字符串字段（缺失返回 null）。 */
    public String string(String key) {
        Object v = payload == null ? null : payload.get(key);
        return v == null ? null : String.valueOf(v);
    }

    /** 取 payload 中的原始值（缺失返回 null）。 */
    public Object get(String key) {
        return payload == null ? null : payload.get(key);
    }
}
