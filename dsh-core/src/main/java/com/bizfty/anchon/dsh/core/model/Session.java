package com.bizfty.anchon.dsh.core.model;

import java.time.Instant;

/**
 * 会话 — 领域值对象（不可变）。
 * <p>
 * 对应 DSH core/session 的 Session；持久化由 dsh-session 模块的实体负责。
 *
 * @param id        会话 id
 * @param title     标题
 * @param model     模型名（可为 null，由部署默认兜底）
 * @param cwd       会话工作区（创建时固化，对应 DSH 的 workspaceRoot）
 * @param createdAt 创建时间
 * @param updatedAt 最后活动时间
 */
public record Session(
        SessionId id,
        String title,
        String model,
        String cwd,
        Instant createdAt,
        Instant updatedAt) {

    public Session withUpdatedAt(Instant updatedAt) {
        return new Session(id, title, model, cwd, createdAt, updatedAt);
    }
}
