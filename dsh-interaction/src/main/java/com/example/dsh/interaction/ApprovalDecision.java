package com.example.dsh.interaction;

/**
 * 审批决策（对应 DSH approval/request 的应答：allowed-once / rejected / unavailable）。
 * <p>
 * fail closed：无应答者或策略为 never 时按拒绝处理。
 */
public record ApprovalDecision(Status status, String message) {

    public enum Status {
        /** 一次性授予（只读一次，无 allow-always）。 */
        ALLOWED,
        REJECTED,
        /** 无应答者可用（fail closed → 按拒绝处理）。 */
        UNAVAILABLE
    }

    public static ApprovalDecision allowed(String message) {
        return new ApprovalDecision(Status.ALLOWED, message);
    }

    public static ApprovalDecision rejected(String message) {
        return new ApprovalDecision(Status.REJECTED, message);
    }

    public static ApprovalDecision unavailable(String message) {
        return new ApprovalDecision(Status.UNAVAILABLE, message);
    }

    public boolean allowed() {
        return status == Status.ALLOWED;
    }
}
