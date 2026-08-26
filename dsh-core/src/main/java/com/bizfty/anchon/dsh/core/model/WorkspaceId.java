package com.bizfty.anchon.dsh.core.model;

/**
 * 工作区标识 — 品牌化字符串（对应 DSH branded workspace id）。
 * <p>
 * 跨边界 id 必须是品牌类型而非裸 String；紧凑构造器做非空校验，fail loud。
 */
public record WorkspaceId(String value) {

    public WorkspaceId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("WorkspaceId 不能为空");
        }
    }

    public static WorkspaceId of(String value) {
        return new WorkspaceId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
