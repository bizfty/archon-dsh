package com.bizfty.anchon.dsh.core.model;

import java.time.Instant;

/**
 * 工作区 — 领域值对象（不可变），对应官方 DSH 的 Workspace。
 * <p>
 * 一个 Workspace 绑定一个主机目录（path，创建时固化并规范化）；会话挂在工作区
 * 之下，会话的 cwd 恒等于所属工作区的 path（官方 connectWorkspace 复用规则依赖
 * 该不变式：同目录下最多一个 blank 会话）。
 *
 * @param id        工作区 id
 * @param path      主机目录绝对路径（规范化、唯一）
 * @param title     显示名（可为 null，前端以目录 basename 兜底）
 * @param createdAt 创建时间
 * @param updatedAt 最后活动时间
 */
public record Workspace(
        WorkspaceId id,
        String path,
        String title,
        Instant createdAt,
        Instant updatedAt) {

    public Workspace withUpdatedAt(Instant updatedAt) {
        return new Workspace(id, path, title, createdAt, updatedAt);
    }

    public Workspace withTitle(String title) {
        return new Workspace(id, path, title, createdAt, updatedAt);
    }
}
