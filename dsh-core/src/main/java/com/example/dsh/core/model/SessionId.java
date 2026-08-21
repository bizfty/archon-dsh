package com.example.dsh.core.model;

/**
 * 会话标识 — 品牌化字符串（对应 DSH branded id）。
 * <p>
 * 跨边界 id 必须是品牌类型而非裸 String；紧凑构造器做非空校验，fail loud。
 */
public record SessionId(String value) {

    public SessionId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("SessionId 不能为空");
        }
    }

    public static SessionId of(String value) {
        return new SessionId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
