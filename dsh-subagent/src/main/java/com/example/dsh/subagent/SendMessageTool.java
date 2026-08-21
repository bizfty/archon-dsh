package com.example.dsh.subagent;

import com.example.dsh.tool.AgentTool;
import com.example.dsh.tool.Tool;
import com.example.dsh.tool.ToolCall;
import com.example.dsh.tool.ToolContext;
import com.example.dsh.tool.ToolResult;
import com.example.dsh.tool.ToolSchema;

import java.util.Map;
import java.util.Optional;

/**
 * send_message 工具 — 向既有子代理发消息并等待回复（对应 DSH subagent/tool-subagent-control 的 send_message）。
 */
@Tool(name = "send_message", description = "向一个已存在的子代理发送消息并等待回复。")
public class SendMessageTool implements AgentTool {

    private final SubagentRunner runner;

    public SendMessageTool(SubagentRunner runner) {
        this.runner = runner;
    }

    @Override
    public String name() {
        return "send_message";
    }

    @Override
    public ToolSchema getSchema() {
        return ToolSchema.builder()
                .name(name())
                .description("给子代理发送消息。")
                .addParameter("agent_id", "string", "子代理 id（来自 list_agents）")
                .addParameter("message", "string", "消息内容")
                .required("agent_id", "message")
                .build();
    }

    @Override
    public ToolResult execute(ToolCall call, ToolContext context) {
        String agentId = call.getString("agent_id");
        String message = call.getString("message");
        if (agentId == null || agentId.isBlank()) {
            return ToolResult.failure("缺少必要参数 agent_id");
        }
        if (message == null || message.isBlank()) {
            return ToolResult.failure("缺少必要参数 message");
        }
        if (context.sessionId() == null) {
            return ToolResult.failure("缺少 sessionId");
        }
        Optional<String> reply = runner.followup(context.sessionId(), agentId, message);
        if (reply.isEmpty()) {
            return ToolResult.failure("子代理不存在: " + agentId + "（先用 list_agents 查看）");
        }
        return ToolResult.success("子代理 " + agentId + " 回复:\n" + reply.get(),
                Map.of("agent_id", agentId, "reply", reply.get()));
    }
}
