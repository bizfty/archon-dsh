package com.bizfty.anchon.dsh.interaction;

/**
 * 审批应答者 SPI（对应 DSH approval/request waterfall 的应答者）。
 * <p>
 * 一个实现可阻塞等待人工应答（如 Web 端等待用户点击）；
 * 返回 empty 表示本应答者无法处理该请求（交给下一个）。
 */
public interface ApprovalProvider {

    /** 应答者名（诊断/日志用）。 */
    String name();

    /**
     * 请求审批并等待应答。
     *
     * @param request   审批请求
     * @param timeoutMs 等待上限
     * @return 决策；无法处理返回 empty
     */
    java.util.Optional<ApprovalDecision> request(ApprovalRequest request, long timeoutMs);
}
