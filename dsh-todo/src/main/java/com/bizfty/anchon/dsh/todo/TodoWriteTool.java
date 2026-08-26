package com.bizfty.anchon.dsh.todo;

import com.bizfty.anchon.dsh.plan.PlanService;
import com.bizfty.anchon.dsh.tool.AgentTool;
import com.bizfty.anchon.dsh.tool.Tool;
import com.bizfty.anchon.dsh.tool.ToolCall;
import com.bizfty.anchon.dsh.tool.ToolContext;
import com.bizfty.anchon.dsh.tool.ToolResult;
import com.bizfty.anchon.dsh.tool.ToolSchema;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * todo_write 工具 — todo 是 DAG 的特殊形式（无依赖计划）：
 * 每次调用把待办列表 upsert 进当前会话的 plan（无 plan 时自动创建无依赖 plan）。
 * <p>
 * 整表替换语义（对齐官方 todo）：todos 为 [{status, title, description, required}]，
 * 其中 status ∈ pending/in_progress/completed/cancelled/skipped/failed；
 * required=true（默认）= 必做项，false = 可跳过。
 * 步骤按 title 匹配更新（保留 id/状态），新标题追加为 pending 步骤，缺失的步骤标记 cancelled。
 */
@Tool(name = "todo_write",
      description = "写入当前任务的完整待办列表（整表替换）。todos 为 [{status, title, description, required}]，"
              + "status ∈ pending/in_progress/completed/cancelled/skipped/failed；required=false 表示可跳过。")
public class TodoWriteTool implements AgentTool {

    private final PlanService dagPlanService;

    public TodoWriteTool(PlanService dagPlanService) {
        this.dagPlanService = dagPlanService;
    }

    @Override
    public String name() {
        return "todo_write";
    }

    @Override
    public ToolSchema getSchema() {
        return ToolSchema.builder()
                .name(name())
                .description("写入完整待办列表（整表替换，非增量）。")
                .addObjectArrayParameter("todos", "待办项数组，每项含 status/title/description/required",
                        java.util.Map.of(
                                "status", new ToolSchema.Parameter("string", "状态: pending/in_progress/completed/cancelled/skipped/failed，默认 pending", null, null, null),
                                "title", new ToolSchema.Parameter("string", "待办标题（必填）", null, null, null),
                                "description", new ToolSchema.Parameter("string", "详细说明", null, null, null),
                                "required", new ToolSchema.Parameter("boolean", "是否必做（默认 true；false = 可跳过）", null, null, null)),
                        "title")
                .required("todos")
                .build();
    }

    @Override
    public ToolResult execute(ToolCall call, ToolContext context) {
        if (context.sessionId() == null) {
            return ToolResult.failure("缺少 sessionId");
        }
        List<Map<String, Object>> raw = call.getList("todos");
        if (raw == null) {
            // 兼容模型直接传字符串数组（如 ["任务A"] → pending 待办）
            List<String> rawStrings = call.getStringList("todos");
            if (rawStrings != null && !rawStrings.isEmpty()) {
                return replaceWithTitles(context, rawStrings);
            }
            return ToolResult.failure("缺少必要参数 todos");
        }
        List<Map<String, Object>> normalized = new ArrayList<>();
        for (Map<String, Object> row : raw) {
            String status = row.get("status") == null ? "pending" : String.valueOf(row.get("status"));
            String title = row.get("title") == null ? "" : String.valueOf(row.get("title"));
            if (title.isBlank()) {
                return ToolResult.failure("待办标题不能为空");
            }
            if (!TodoItem.isValidStatus(status)) {
                return ToolResult.failure("非法状态: " + status + "（可选: " + TodoItem.VALID_STATUSES + "）");
            }
            normalized.add(row);
        }
        return upsert(context, normalized);
    }

    private ToolResult replaceWithTitles(ToolContext context, List<String> titles) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String t : titles) {
            Map<String, Object> m = new HashMap<>();
            m.put("status", "pending");
            m.put("title", t);
            rows.add(m);
        }
        return upsert(context, rows);
    }

    /** 整表替换：按 title 匹配更新，新标题追加，缺失的旧步骤置 cancelled。 */
    private ToolResult upsert(ToolContext context, List<Map<String, Object>> rows) {
        var sessionId = context.sessionId();
        // 当前 plan（无 → 自动创建"待办"计划）
        PlanService.PlanDetail plan;
        var current = dagPlanService.currentPlan(sessionId);
        if (current.isPresent() && !"completed".equals(current.get().plan().getStatus())
                && !"abandoned".equals(current.get().plan().getStatus())) {
            plan = current.get();
        } else {
            // 建一个占位计划，下面 append 步骤
            List<PlanService.StepSpec> placeholder = List.of(
                    new PlanService.StepSpec("todo-init", "初始化", "", false));
            plan = dagPlanService.createPlan(sessionId, "待办", placeholder, List.of());
            dagPlanService.deleteStep(plan.plan().getId(), "todo-init");
            plan = dagPlanService.loadDetail(plan.plan().getId());
        }

        // 现有步骤按 title 索引
        Map<String, com.bizfty.anchon.dsh.plan.PlanStepEntity> byTitle = new HashMap<>();
        for (var step : plan.steps()) {
            byTitle.put(step.getTitle(), step);
        }
        int seq = 0;
        for (Map<String, Object> row : rows) {
            String status = String.valueOf(row.get("status"));
            String title = String.valueOf(row.get("title"));
            String desc = row.get("description") == null ? "" : String.valueOf(row.get("description"));
            boolean required = row.get("required") == null
                    || Boolean.TRUE.equals(row.get("required"))
                    || "true".equalsIgnoreCase(String.valueOf(row.get("required")));
            var existing = byTitle.remove(title);
            if (existing != null) {
                dagPlanService.updateStep(plan.plan().getId(), existing.getId(),
                        title, desc, status, required, seq);
            } else {
                dagPlanService.appendStep(plan.plan().getId(), title, desc, status, required);
            }
            seq++;
        }
        // 缺失的旧步骤 → cancelled（不再出现在清单里）
        for (var stale : byTitle.values()) {
            dagPlanService.updateStepStatus(plan.plan().getId(), stale.getId(), "cancelled");
        }
        long pending = rows.stream().filter(r ->
                !"completed".equals(String.valueOf(r.get("status")))
                        && !"cancelled".equals(String.valueOf(r.get("status")))
                        && !"skipped".equals(String.valueOf(r.get("status")))
                        && !"failed".equals(String.valueOf(r.get("status")))).count();
        return ToolResult.success("待办已更新: " + rows.size() + " 项 (未完成 " + pending + " 项)",
                Map.of("total", rows.size(), "pending", pending));
    }
}
