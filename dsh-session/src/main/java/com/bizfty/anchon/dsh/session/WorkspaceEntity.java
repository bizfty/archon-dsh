package com.bizfty.anchon.dsh.session;

import com.bizfty.anchon.dsh.core.model.Workspace;
import com.bizfty.anchon.dsh.core.model.WorkspaceId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 工作区持久化实体。
 * <p>
 * path 唯一约束：同一主机目录只能注册一次（官方 Workspace 语义：一个目录一个
 * 工作区，重复添加按 idempotent 解析到既有工作区）。
 */
@Entity
@Table(name = "anchon_workspace")
public class WorkspaceEntity {

    @Id
    @Column(length = 64)
    private String id;

    /** 主机目录绝对路径（规范化），唯一。 */
    @Column(length = 1024, unique = true, nullable = false)
    private String path;

    @Column(length = 255)
    private String title;

    private Instant createdAt;
    private Instant updatedAt;

    protected WorkspaceEntity() {
    }

    public static WorkspaceEntity from(Workspace workspace) {
        WorkspaceEntity e = new WorkspaceEntity();
        e.id = workspace.id().value();
        e.path = workspace.path();
        e.title = workspace.title();
        e.createdAt = workspace.createdAt();
        e.updatedAt = workspace.updatedAt();
        return e;
    }

    public Workspace toDomain() {
        return new Workspace(WorkspaceId.of(id), path, title, createdAt, updatedAt);
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
