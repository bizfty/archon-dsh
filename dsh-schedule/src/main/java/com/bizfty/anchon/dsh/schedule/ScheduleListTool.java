package com.bizfty.anchon.dsh.schedule;

import com.bizfty.anchon.dsh.tool.AgentTool;
import com.bizfty.anchon.dsh.tool.Tool;
import com.bizfty.anchon.dsh.tool.ToolCall;
import com.bizfty.anchon.dsh.tool.ToolContext;
import com.bizfty.anchon.dsh.tool.ToolResult;
import com.bizfty.anchon.dsh.tool.ToolSchema;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * schedule_list 工具 — 列出当前会话的提醒。
 */
@Tool(name = "schedule_list", description = "列出当前会话的提醒及其状态。")
public class ScheduleListTool implements AgentTool {

    private final ScheduleService scheduleService;

    public ScheduleListTool(ScheduleService scheduleService) {
        this.scheduleService = scheduleService;
    }

    @Override
    public String name() {
        return "schedule_list";
    }

    @Override
    public ToolSchema getSchema() {
        return ToolSchema.builder()
                .name(name())
                .description("列出提醒。")
                .build();
    }

    @Override
    public ToolResult execute(ToolCall call, ToolContext context) {
        if (context.sessionId() == null) {
            return ToolResult.failure("缺少 sessionId");
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (ScheduleEntry entry : scheduleService.list(context.sessionId())) {
            items.add(Map.of(
                    "id", entry.id(),
                    "status", entry.status().name(),
                    "due_at", entry.dueAt().toString(),
                    "message", entry.message()));
        }
        return ToolResult.success("共 " + items.size() + " 个提醒", Map.of("schedules", items));
    }
}
