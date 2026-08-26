package com.bizfty.anchon.dsh.api;

import com.bizfty.anchon.dsh.core.model.SessionId;
import com.bizfty.anchon.dsh.plan.PlanService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DAG 计划 API — 创建/查询/推进计划（对应 plan_create / plan_step_update / plan_get 的人类控制面）。
 */
@RestController
@RequestMapping("/api/sessions")
public class PlanController {

    private final PlanService planService;

    public PlanController(PlanService planService) {
        this.planService = planService;
    }

    /** 当前会话的 DAG 计划（无 → 空 map 带 hasPlan=false）。 */
    @GetMapping("/{sessionId}/plan")
    public Map<String, Object> currentPlan(@PathVariable String sessionId) {
        return planService.currentPlan(SessionId.of(sessionId))
                .map(detail -> {
                    Map<String, Object> view = planService.toApiView(detail);
                    view.put("hasPlan", true);
                    return view;
                })
                .orElseGet(() -> Map.of("hasPlan", false));
    }

    /** 创建 DAG 计划。 */
    @PostMapping("/{sessionId}/plan")
    public Map<String, Object> createPlan(@PathVariable String sessionId,
                                          @RequestBody CreatePlanRequest request) {
        PlanService.PlanDetail detail = planService.createPlan(
                SessionId.of(sessionId),
                request.title(),
                request.steps().stream()
                        .map(s -> new PlanService.StepSpec(s.id(), s.title(), s.description(),
                                s.required() == null || s.required(),
                                s.kind() == null || s.kind().isBlank() ? "task" : s.kind()))
                        .toList(),
                request.dependencies() == null ? List.of() : request.dependencies().stream()
                        .map(d -> new PlanService.DepSpec(d.step(), d.dependsOn()))
                        .toList());
        return planService.toApiView(detail);
    }

    /** 更新步骤状态。 */
    @PostMapping("/{sessionId}/plan/steps/{stepId}/status")
    public Map<String, Object> updateStepStatus(@PathVariable String sessionId,
                                                @PathVariable String stepId,
                                                @RequestBody UpdateStepRequest request) {
        String planId = request.planId();
        if (planId == null || planId.isBlank()) {
            // 未给 plan_id 时用当前活动计划
            PlanService.PlanDetail current = planService
                    .currentPlan(SessionId.of(sessionId))
                    .orElseThrow(() -> new IllegalArgumentException("会话无当前计划，请提供 plan_id"));
            planId = current.plan().getId();
        }
        PlanService.PlanDetail detail = planService.updateStepStatus(planId, stepId, request.status());
        return planService.toApiView(detail);
    }

    /** 完成整个计划。 */
    @PostMapping("/{sessionId}/plan/complete")
    public Map<String, Object> completePlan(@PathVariable String sessionId,
                                            @RequestBody(required = false) PlanIdRequest request) {
        String planId = resolvePlanId(sessionId, request == null ? null : request.planId());
        return planService.toApiView(planService.completePlan(planId));
    }

    /**
     * 步骤执行细节：返回该步骤关联的工具调用序列（plan_step_execution 表）。
     * 并行执行下每个调用独立关联，天然准确。
     */
    @GetMapping("/{sessionId}/plan/steps/{stepId}/execution")
    public Map<String, Object> stepExecution(@PathVariable String sessionId,
                                             @PathVariable String stepId) {
        Map<String, Object> view = new LinkedHashMap<>();
        var current = planService.currentPlan(SessionId.of(sessionId));
        if (current.isEmpty()) {
            view.put("error", "会话无当前计划");
            return view;
        }
        var detail = current.get();
        var target = detail.steps().stream().filter(s -> s.getId().equals(stepId)).findFirst();
        if (target.isEmpty()) {
            view.put("error", "步骤不存在: " + stepId);
            return view;
        }
        List<Map<String, Object>> calls = new ArrayList<>();
        for (var exec : planService.listExecutions(stepId)) {
            Map<String, Object> call = new LinkedHashMap<>();
            call.put("tool", exec.getToolName());
            call.put("args", exec.getArgsSummary());
            call.put("status", exec.getStatus());
            call.put("at", exec.getCreatedAt().toString());
            calls.add(call);
        }
        view.put("stepId", stepId);
        view.put("title", target.get().getTitle());
        view.put("status", target.get().getStatus());
        view.put("calls", calls);
        return view;
    }

    /** 放弃计划。 */
    @PostMapping("/{sessionId}/plan/abandon")
    public Map<String, Object> abandonPlan(@PathVariable String sessionId,
                                           @RequestBody(required = false) PlanIdRequest request) {
        String planId = resolvePlanId(sessionId, request == null ? null : request.planId());
        return planService.toApiView(planService.abandonPlan(planId));
    }

    /**
     * 审阅步骤（批准/撤回批准；审阅门）：doc 类步骤（proposal/spec/design/doc）
     * reviewed=true 后才进入 nextSteps（可执行）。task 类实现步骤无需审阅。
     */
    @PostMapping("/{sessionId}/plan/steps/{stepId}/review")
    public Map<String, Object> reviewStep(@PathVariable String sessionId,
                                          @PathVariable String stepId,
                                          @RequestBody ReviewStepRequest request) {
        String planId = request.planId();
        if (planId == null || planId.isBlank()) {
            PlanService.PlanDetail current = planService.currentPlan(SessionId.of(sessionId))
                    .orElseThrow(() -> new IllegalArgumentException("会话无当前计划，请提供 plan_id"));
            planId = current.plan().getId();
        }
        PlanService.PlanDetail detail = planService.reviewStep(planId, stepId,
                Boolean.TRUE.equals(request.reviewed()));
        return planService.toApiView(detail);
    }

    private String resolvePlanId(String sessionId, String planId) {
        if (planId != null && !planId.isBlank()) {
            return planId;
        }
        return planService.currentPlan(SessionId.of(sessionId))
                .orElseThrow(() -> new IllegalArgumentException("会话无当前计划，请提供 plan_id"))
                .plan().getId();
    }

    /** 创建计划请求。 */
    public record CreatePlanRequest(String title, List<StepReq> steps, List<DepReq> dependencies) {
    }

    public record StepReq(String id, String title, String description, Boolean required, String kind) {

        public StepReq(String id, String title, String description, Boolean required) {
            this(id, title, description, required, "task");
        }
    }

    public record DepReq(String step, String dependsOn) {
    }

    public record UpdateStepRequest(String planId, String status) {
    }

    public record PlanIdRequest(String planId) {
    }

    /** 审阅步骤请求：reviewed=true 批准 / false 撤回。 */
    public record ReviewStepRequest(String planId, Boolean reviewed) {
    }
}
