package com.bizfty.anchon.dsh.interaction;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * 审批服务 — 聚合应答者，fail closed（无应答者/超时/策略 never → 拒绝）。
 * <p>
 * 对应 DSH interaction/user-approval：一次性授予，无 allow-always。
 */
@Service
public class ApprovalService {

    private final ObjectProvider<ApprovalProvider> providers;
    private final ApprovalPolicyService policyService;
    private final long defaultTimeoutMs;

    public ApprovalService(ObjectProvider<ApprovalProvider> providers,
                           ApprovalPolicyService policyService,
                           @org.springframework.beans.factory.annotation.Value(
                                   "${dsh.interaction.approval-timeout-ms:60000}") long defaultTimeoutMs) {
        this.providers = providers;
        this.policyService = policyService;
        this.defaultTimeoutMs = defaultTimeoutMs;
    }

    /**
     * 请求审批并等待应答。
     *
     * @param toolName  工具名
     * @param reason    理由
     * @param context   工具上下文（含会话）
     * @return 决策（fail closed：任何异常/超时/无应答者 → REJECTED/UNAVAILABLE）
     */
    public ApprovalDecision request(String toolName, String reason, com.bizfty.anchon.dsh.tool.ToolContext context) {
        // 策略 never：直接拒绝，不打扰应答者
        if (policyService.policyFor(context.sessionId()) == ApprovalPolicy.NEVER) {
            return ApprovalDecision.rejected("审批策略为 never，拒绝执行 " + toolName);
        }
        List<ApprovalProvider> list = providers.orderedStream().toList();
        if (list.isEmpty()) {
            return ApprovalDecision.unavailable("无审批应答者，fail closed 拒绝 " + toolName);
        }
        ApprovalRequest request = ApprovalRequest.of("appr_" + UUID.randomUUID(), toolName, reason, context);
        for (ApprovalProvider provider : list) {
            try {
                java.util.Optional<ApprovalDecision> decision = provider.request(request, defaultTimeoutMs);
                if (decision.isPresent()) {
                    return decision.get();
                }
            } catch (Exception e) {
                // 单个应答者失败：尝试下一个
            }
        }
        return ApprovalDecision.unavailable("所有审批应答者均未应答，fail closed 拒绝 " + toolName);
    }
}
