package com.example.dsh.schedule;

import com.example.dsh.tool.AgentTool;
import com.example.dsh.tool.Tool;
import com.example.dsh.tool.ToolCall;
import com.example.dsh.tool.ToolContext;
import com.example.dsh.tool.ToolResult;
import com.example.dsh.tool.ToolSchema;

import java.util.Map;

/**
 * schedule_create 工具 — 安排一个延迟提醒（对应 DSH schedule 的提醒创建）。
 */
@Tool(name = "schedule_create", description = "安排一个延迟提醒：delay_ms 后向会话注入一条消息（例如让用户稍后提醒自己）。")
public class ScheduleCreateTool implements AgentTool {

    private final ScheduleService scheduleService;

    public ScheduleCreateTool(ScheduleService scheduleService) {
        this.scheduleService = scheduleService;
    }

    @Override
    public String name() {
        return "schedule_create";
    }

    @Override
    public ToolSchema getSchema() {
        return ToolSchema.builder()
                .name(name())
                .description("安排延迟提醒。")
                .addParameter("delay_ms", "integer", "延迟毫秒")
                .addParameter("message", "string", "提醒内容")
                .required("delay_ms", "message")
                .build();
    }

    @Override
    public ToolResult execute(ToolCall call, ToolContext context) {
        Integer delayMs = call.getInt("delay_ms", null);
        String message = call.getString("message");
        if (delayMs == null || delayMs < 0) {
            return ToolResult.failure("缺少必要参数 delay_ms（非负毫秒）");
        }
        if (message == null || message.isBlank()) {
            return ToolResult.failure("缺少必要参数 message");
        }
        if (context.sessionId() == null) {
            return ToolResult.failure("缺少 sessionId");
        }
        ScheduleEntry entry = scheduleService.schedule(context.sessionId(), delayMs, message);
        return ToolResult.success("提醒已安排: " + entry.id() + "（" + delayMs + " ms 后触发）",
                Map.of("schedule_id", entry.id(), "due_at", entry.dueAt().toString()));
    }
}
