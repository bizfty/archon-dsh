package com.bizfty.anchon.dsh.plan;

import com.bizfty.anchon.dsh.core.model.SessionId;
import com.bizfty.anchon.dsh.tool.AgentTool;
import com.bizfty.anchon.dsh.tool.ToolCall;
import com.bizfty.anchon.dsh.tool.ToolContext;
import com.bizfty.anchon.dsh.tool.ToolResult;
import com.bizfty.anchon.dsh.tool.ToolSchema;
import com.bizfty.anchon.dsh.util.JsonUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * DAG 计划模型工具：plan_create / plan_step_update / plan_get。
 * <p>
 * 让模型以结构化 DAG（步骤 + 依赖）建立并推进计划，对应"计划支持 DAG"的目标。
 */
@Component
public class PlanTools {

    private final PlanService dagPlanService;
    private final JsonUtils jsonUtils;

    public PlanTools(PlanService dagPlanService, JsonUtils jsonUtils) {
        this.dagPlanService = dagPlanService;
        this.jsonUtils = jsonUtils;
    }

    /** 创建 DAG 计划：标题 + 步骤（id/title/description）+ 依赖（step→dependsOn）。 */
    @Component
    public static class CreatePlanTool implements AgentTool {
        private final PlanService dagPlanService;
        private final JsonUtils jsonUtils;

        public CreatePlanTool(PlanService dagPlanService, JsonUtils jsonUtils) {
            this.dagPlanService = dagPlanService;
            this.jsonUtils = jsonUtils;
        }

        @Override
        public String name() {
            return "plan_create";
        }

        @Override
        public ToolSchema getSchema() {
            return ToolSchema.builder()
                    .name(name())
                    .description("创建 DAG 计划：结构化步骤列表与依赖关系（A 依赖 B = A 完成后才能做 B）。" +
                            "每步给稳定 id、标题、说明；依赖可选。含环会拒绝。" +
                            "步骤可带 kind（task/proposal/spec/design/doc，默认 task）：" +
                            "规划工件（非 task）须人类用 plan_step_review 批准后才可执行。")
                    .addParameter("title", "string", "计划标题")
                    .addParameter("steps", "string", "步骤数组 JSON：[{\"id\":\"s1\",\"title\":\"...\",\"description\":\"...\",\"kind\":\"spec\"}]")
                    .addParameter("dependencies", "string", "依赖数组 JSON：[{\"step\":\"s2\",\"dependsOn\":\"s1\"}]（可选）")
                    .required("title", "steps")
                    .build();
        }

        @Override
        public ToolResult execute(ToolCall call, ToolContext context) {
            try {
                if (context.sessionId() == null) {
                    return ToolResult.failure("缺少 sessionId");
                }
                String title = call.getString("title");
                String stepsJson = call.getString("steps");
                String depsJson = call.getString("dependencies", "[]");
                List<PlanService.StepSpec> steps = parseSteps(stepsJson);
                List<PlanService.DepSpec> deps = parseDeps(depsJson);
                PlanService.PlanDetail detail = dagPlanService.createPlan(
                        context.sessionId(), title, steps, deps);
                Map<String, Object> view = dagPlanService.toApiView(detail);
                return ToolResult.success("计划已创建: " + detail.plan().getTitle()
                        + "（" + steps.size() + " 步骤, " + deps.size() + " 依赖）",
                        view);
            } catch (IllegalArgumentException e) {
                return ToolResult.failure(e.getMessage());
            }
        }

        private List<PlanService.StepSpec> parseSteps(String json) {
            List<Map<String, Object>> list = jsonUtils.toList(json);
            List<PlanService.StepSpec> result = new ArrayList<>();
            for (Map<String, Object> m : list) {
                String id = String.valueOf(m.get("id"));
                if (id == null || id.isBlank() || "null".equals(id)) {
                    id = "s" + (result.size() + 1);
                }
                String kind = m.get("kind") == null || "null".equals(String.valueOf(m.get("kind")))
                        ? "task" : String.valueOf(m.get("kind"));
                result.add(new PlanService.StepSpec(id,
                        String.valueOf(m.get("title")),
                        m.get("description") == null ? "" : String.valueOf(m.get("description")),
                        true, kind));
            }
            return result;
        }

        private List<PlanService.DepSpec> parseDeps(String json) {
            List<Map<String, Object>> list = jsonUtils.toList(json);
            List<PlanService.DepSpec> result = new ArrayList<>();
            for (Map<String, Object> m : list) {
                result.add(new PlanService.DepSpec(
                        String.valueOf(m.get("step")),
                        String.valueOf(m.get("dependsOn"))));
            }
            return result;
        }
    }

    /** 更新步骤状态（pending/in_progress/completed/blocked）。 */
    @Component
    public static class UpdateStepStatusTool implements AgentTool {
        private final PlanService dagPlanService;
        private final JsonUtils jsonUtils;

        public UpdateStepStatusTool(PlanService dagPlanService, JsonUtils jsonUtils) {
            this.dagPlanService = dagPlanService;
            this.jsonUtils = jsonUtils;
        }

        @Override
        public String name() {
            return "plan_step_update";
        }

        @Override
        public ToolSchema getSchema() {
            return ToolSchema.builder()
                    .name(name())
                    .description("更新 DAG 计划步骤状态（pending/in_progress/completed/blocked）。" +
                            "完成一步后自动推进计划；查询下一步用 plan_get。")
                    .addParameter("plan_id", "string", "计划 id")
                    .addParameter("step_id", "string", "步骤 id")
                    .addParameter("status", "string", "新状态: pending|in_progress|completed|blocked")
                    .required("plan_id", "step_id", "status")
                    .build();
        }

        @Override
        public ToolResult execute(ToolCall call, ToolContext context) {
            try {
                String planId = call.getString("plan_id");
                String stepId = call.getString("step_id");
                String status = call.getString("status");
                PlanService.PlanDetail detail = dagPlanService.updateStepStatus(planId, stepId, status);
                return ToolResult.success("步骤 " + stepId + " → " + status,
                        dagPlanService.toApiView(detail));
            } catch (IllegalArgumentException e) {
                return ToolResult.failure(e.getMessage());
            }
        }
    }

    /** 查询当前计划（含 nextSteps）。 */
    @Component
    public static class GetPlanTool implements AgentTool {
        private final PlanService dagPlanService;
        private final JsonUtils jsonUtils;

        public GetPlanTool(PlanService dagPlanService, JsonUtils jsonUtils) {
            this.dagPlanService = dagPlanService;
            this.jsonUtils = jsonUtils;
        }

        @Override
        public String name() {
            return "plan_get";
        }

        @Override
        public ToolSchema getSchema() {
            return ToolSchema.builder()
                    .name(name())
                    .description("读取当前会话的 DAG 计划（步骤状态 + 依赖 + 下一步可执行步骤）。无计划返回空。")
                    .build();
        }

        @Override
        public ToolResult execute(ToolCall call, ToolContext context) {
            if (context.sessionId() == null) {
                return ToolResult.failure("缺少 sessionId");
            }
            return dagPlanService.currentPlan(context.sessionId())
                    .map(detail -> ToolResult.success("当前计划: " + detail.plan().getTitle(),
                            dagPlanService.toApiView(detail)))
                    .orElseGet(() -> ToolResult.success("会话无当前 DAG 计划"));
        }
    }

    /** 审阅步骤（批准/撤回批准）：doc 类步骤（proposal/spec/design/doc）须批准后才可执行。 */
    @Component
    public static class ReviewStepTool implements AgentTool {
        private final PlanService dagPlanService;

        public ReviewStepTool(PlanService dagPlanService) {
            this.dagPlanService = dagPlanService;
        }

        @Override
        public String name() {
            return "plan_step_review";
        }

        @Override
        public ToolSchema getSchema() {
            return ToolSchema.builder()
                    .name(name())
                    .description("审阅 DAG 计划步骤（审阅门）：doc 类步骤（proposal/spec/design/doc）" +
                            "reviewed=true 后才进入下一步可执行。task 类实现步骤无需审阅。")
                    .addParameter("plan_id", "string", "计划 id")
                    .addParameter("step_id", "string", "步骤 id")
                    .addParameter("reviewed", "boolean", "true=批准 / false=撤回批准")
                    .required("plan_id", "step_id", "reviewed")
                    .build();
        }

        @Override
        public ToolResult execute(ToolCall call, ToolContext context) {
            try {
                String planId = call.getString("plan_id");
                String stepId = call.getString("step_id");
                boolean reviewed = Boolean.parseBoolean(call.getString("reviewed", "false"));
                PlanService.PlanDetail detail = dagPlanService.reviewStep(planId, stepId, reviewed);
                return ToolResult.success("步骤 " + stepId + " 审阅状态 → "
                        + (reviewed ? "已批准" : "已撤回"),
                        dagPlanService.toApiView(detail));
            } catch (IllegalArgumentException e) {
                return ToolResult.failure(e.getMessage());
            }
        }
    }
}
