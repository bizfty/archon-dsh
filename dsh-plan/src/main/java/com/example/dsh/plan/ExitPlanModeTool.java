package com.example.dsh.plan;

import com.example.dsh.tool.AgentTool;
import com.example.dsh.tool.Tool;
import com.example.dsh.tool.ToolCall;
import com.example.dsh.tool.ToolContext;
import com.example.dsh.tool.ToolResult;
import com.example.dsh.tool.ToolSchema;

import java.util.Map;

/**
 * exit_plan_mode 工具 — 结束计划模式并提交计划供评审（对应 DSH plan-mode 的退出工具）。
 */
@Tool(name = "exit_plan_mode",
      description = "结束计划模式：提交当前计划内容并退出，供用户评审后进入执行。")
public class ExitPlanModeTool implements AgentTool {

    private final PlanModeService planModeService;

    public ExitPlanModeTool(PlanModeService planModeService) {
        this.planModeService = planModeService;
    }

    @Override
    public String name() {
        return "exit_plan_mode";
    }

    @Override
    public ToolSchema getSchema() {
        return ToolSchema.builder()
                .name(name())
                .description("退出计划模式并提交计划。")
                .addParameter("plan", "string", "完整计划内容（markdown）")
                .required("plan")
                .build();
    }

    @Override
    public ToolResult execute(ToolCall call, ToolContext context) {
        if (context.sessionId() == null) {
            return ToolResult.failure("缺少 sessionId");
        }
        String plan = call.getString("plan", "");
        if (plan.isBlank()) {
            return ToolResult.failure("缺少必要参数 plan");
        }
        planModeService.updatePlan(context.sessionId(), plan);
        planModeService.exitPlanMode(context.sessionId());
        return ToolResult.success("计划已提交并退出计划模式",
                Map.of("plan", plan, "plan_mode", false));
    }
}
