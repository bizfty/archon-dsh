package com.bizfty.anchon.dsh.api;

import com.bizfty.anchon.dsh.core.model.SessionId;
import com.bizfty.anchon.dsh.plan.PlanService;
import com.bizfty.anchon.dsh.plan.PlanStepEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 待办视图 API — todo 是 DAG 的特殊形式（无依赖计划）：
 * 直接读当前会话 plan 的 plan_step 扁平化（todo 面板），不设独立 todo 表。
 */
@RestController
@RequestMapping("/api/sessions")
public class TodoController {

    private final PlanService dagPlanService;

    public TodoController(PlanService dagPlanService) {
        this.dagPlanService = dagPlanService;
    }

    /** 会话 todo 清单（= 当前 plan 的步骤扁平化；无 plan → hasTodos=false）。 */
    @GetMapping("/{sessionId}/todos")
    public Map<String, Object> todos(@PathVariable String sessionId) {
        var planOpt = dagPlanService.currentPlan(SessionId.of(sessionId));
        Map<String, Object> view = new LinkedHashMap<>();
        if (planOpt.isEmpty()) {
            view.put("hasTodos", false);
            return view;
        }
        List<PlanStepEntity> steps = planOpt.get().steps();
        if (steps.isEmpty()) {
            view.put("hasTodos", false);
            return view;
        }
        long completed = steps.stream().filter(s -> "completed".equals(s.getStatus())).count();
        long inProgress = steps.stream().filter(s -> "in_progress".equals(s.getStatus())).count();
        long pending = steps.stream().filter(s ->
                !PlanService.TERMINAL_STATUSES.contains(s.getStatus())).count();
        view.put("hasTodos", true);
        view.put("planId", planOpt.get().plan().getId());
        view.put("planTitle", planOpt.get().plan().getTitle());
        view.put("total", steps.size());
        view.put("completed", completed);
        view.put("inProgress", inProgress);
        view.put("pending", pending);
        view.put("items", steps.stream().map(step -> Map.of(
                "id", step.getId(),
                "status", step.getStatus(),
                "title", step.getTitle(),
                "description", step.getDescription() == null ? "" : step.getDescription(),
                "planStepId", step.getId(),
                "required", step.isRequired())).toList());
        return view;
    }
}
