package com.example.dsh.subagent;

import com.example.dsh.tool.AgentTool;
import com.example.dsh.tool.Tool;
import com.example.dsh.tool.ToolCall;
import com.example.dsh.tool.ToolContext;
import com.example.dsh.tool.ToolResult;
import com.example.dsh.tool.ToolSchema;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * list_agents 工具 — 列出当前会话委托的子代理（对应 DSH subagent/tool-subagent-control 的 list_agents）。
 */
@Tool(name = "list_agents", description = "列出当前会话的子代理及其状态。")
public class ListAgentsTool implements AgentTool {

    private final SubagentRegistry registry;

    public ListAgentsTool(SubagentRegistry registry) {
        this.registry = registry;
    }

    @Override
    public String name() {
        return "list_agents";
    }

    @Override
    public ToolSchema getSchema() {
        return ToolSchema.builder()
                .name(name())
                .description("列出子代理。")
                .build();
    }

    @Override
    public ToolResult execute(ToolCall call, ToolContext context) {
        if (context.sessionId() == null) {
            return ToolResult.failure("缺少 sessionId");
        }
        List<Map<String, Object>> agents = new ArrayList<>();
        for (SubagentHandle handle : registry.list(context.sessionId())) {
            agents.add(Map.of(
                    "id", handle.id(),
                    "status", handle.status().name(),
                    "depth", handle.delegationDepth(),
                    "content", handle.lastContent() == null ? "" : handle.lastContent()));
        }
        return ToolResult.success("共 " + agents.size() + " 个子代理", Map.of("agents", agents));
    }
}
