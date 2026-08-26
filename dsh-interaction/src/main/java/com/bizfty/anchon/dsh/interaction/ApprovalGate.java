package com.bizfty.anchon.dsh.interaction;

import com.bizfty.anchon.dsh.core.event.SessionEventBus;
import com.bizfty.anchon.dsh.core.event.SessionEventType;
import com.bizfty.anchon.dsh.tool.ToolCall;
import com.bizfty.anchon.dsh.tool.ToolContext;
import com.bizfty.anchon.dsh.tool.ToolExecutionPipeline;
import com.bizfty.anchon.dsh.tool.ToolPreExecuteGate;
import com.bizfty.anchon.dsh.tool.ToolRegistry;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 审批门 — 挂在工具执行管线 pre-execute 阶段（对应 DSH tools/pre-execute 的 ask）。
 * <p>
 * 只对声明 requiresApproval 的工具生效；deny 单调：被本门拒绝后下游不再放行。
 * fail closed：无应答者/超时/策略 never → 拒绝。
 * 阻塞等待应答前会发布 APPROVAL_REQUESTED 事件（供 SSE 推送"待审批"通知，
 * 用户经 REST 端点应答后挂起线程恢复）。
 */
@Component
public class ApprovalGate implements ToolPreExecuteGate {

    private final ApprovalService approvalService;
    private final ToolRegistry registry;
    private final SessionEventBus eventBus;

    public ApprovalGate(ApprovalService approvalService, ToolRegistry registry, SessionEventBus eventBus) {
        this.approvalService = approvalService;
        this.registry = registry;
        this.eventBus = eventBus;
    }

    @Override
    public int order() {
        return 0;
    }

    @Override
    public Optional<String> check(ToolCall call, ToolContext context) {
        if (!registry.hasTool(call.name())) {
            return Optional.empty(); // 未知工具由管线统一处理
        }
        if (!registry.getTool(call.name()).requiresApproval()) {
            return Optional.empty();
        }
        String reason = "工具 " + call.name() + " 需要人工审批"
                + (context.executionId() != null ? "（execution=" + context.executionId() + "）" : "");
        if (context.executionId() != null) {
            eventBus.publish(context.sessionId(), SessionEventType.APPROVAL_REQUESTED,
                    java.util.Map.of(
                            "tool", call.name(),
                            "callId", call.id(),
                            "reason", reason,
                            "executionId", context.executionId()));
        }
        ApprovalDecision decision = approvalService.request(call.name(), reason, context);
        if (decision.allowed()) {
            return Optional.empty();
        }
        return Optional.of("审批未通过: " + decision.message());
    }
}
