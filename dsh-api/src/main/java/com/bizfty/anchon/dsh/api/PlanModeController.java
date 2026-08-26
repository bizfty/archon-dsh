package com.bizfty.anchon.dsh.api;

import com.bizfty.anchon.dsh.core.model.SessionId;
import com.bizfty.anchon.dsh.plan.PlanModeService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 计划模式 API — 前端/用户切换计划模式、提交文本计划（对应 DSH plan-mode 的人类控制面）。
 * <p>
 * 会话级设置（session.&lt;id&gt;/plan-mode）：进入后 system prompt 注入
 * "只规划、不实现"约束（PlanPromptSection），模型调研后调用 exit_plan_mode
 * 提交计划；用户批准后退出计划模式进入执行。
 */
@RestController
@RequestMapping("/api/sessions")
public class PlanModeController {

    private final PlanModeService planModeService;

    public PlanModeController(PlanModeService planModeService) {
        this.planModeService = planModeService;
    }

    /** 当前计划模式状态与已提交的计划文本。 */
    @GetMapping("/{sessionId}/plan-mode")
    public Map<String, Object> planModeState(@PathVariable String sessionId) {
        SessionId id = SessionId.of(sessionId);
        return Map.of(
                "active", planModeService.isActive(id),
                "planText", planModeService.planText(id));
    }

    /** 进入计划模式（只规划，不实现）。 */
    @PostMapping("/{sessionId}/plan-mode/enter")
    public Map<String, Object> enterPlanMode(@PathVariable String sessionId) {
        SessionId id = SessionId.of(sessionId);
        planModeService.enterPlanMode(id);
        return Map.of("active", true);
    }

    /** 退出计划模式（批准/放弃计划后进入执行）。 */
    @PostMapping("/{sessionId}/plan-mode/exit")
    public Map<String, Object> exitPlanMode(@PathVariable String sessionId) {
        SessionId id = SessionId.of(sessionId);
        planModeService.exitPlanMode(id);
        return Map.of("active", false);
    }

    /** 人类提交文本计划（等价模型 exit_plan_mode：保存计划并退出计划模式）。 */
    @PostMapping("/{sessionId}/plan-mode")
    public Map<String, Object> submitPlan(@PathVariable String sessionId,
                                          @RequestBody(required = false) SubmitPlanRequest request) {
        SessionId id = SessionId.of(sessionId);
        String plan = request == null || request.plan() == null ? "" : request.plan();
        if (plan.isBlank()) {
            throw new IllegalArgumentException("计划内容不能为空");
        }
        planModeService.updatePlan(id, plan);
        planModeService.exitPlanMode(id);
        return Map.of("active", false, "planText", plan);
    }

    /** 提交计划请求体。 */
    public record SubmitPlanRequest(String plan) {
    }
}
