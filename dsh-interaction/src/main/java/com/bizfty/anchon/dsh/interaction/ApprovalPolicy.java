package com.bizfty.anchon.dsh.interaction;

/**
 * 审批策略（对应 DSH approval/policy：ask / never）。
 */
public enum ApprovalPolicy {
    /** 需要审批的工具发起询问（默认）。 */
    ASK,
    /** 永不询问 — 需要审批的工具直接拒绝。 */
    NEVER
}
