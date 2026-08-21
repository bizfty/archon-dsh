package com.example.dsh.subagent;

import com.example.dsh.tool.AgentTool;
import com.example.dsh.tool.Tool;
import com.example.dsh.tool.ToolCall;
import com.example.dsh.tool.ToolContext;
import com.example.dsh.tool.ToolResult;
import com.example.dsh.tool.ToolSchema;

import java.util.Map;

/**
 * subagent 工具 — 委托子代理执行任务并返回结果（对应 DSH subagent/tool-subagent 的 one-shot 前台模式）。
 * <p>
 * 深度守卫：委托深度超限返回结构化错误（子代理不能自我加宽）。
 */
@Tool(name = "subagent", description = "委托一个子代理执行独立任务并返回结果。适合可并行/可隔离的子任务。")
public class SubagentTool implements AgentTool {

    private final SubagentRunner runner;

    public SubagentTool(SubagentRunner runner) {
        this.runner = runner;
    }

    @Override
    public String name() {
        return "subagent";
    }

    @Override
    public ToolSchema getSchema() {
        return ToolSchema.builder()
                .name(name())
                .description("启动子代理执行任务。")
                .addParameter("prompt", "string", "给子代理的完整任务说明")
                .addParameter("model", "string", "子代理模型（可选）")
                .required("prompt")
                .build();
    }

    @Override
    public ToolResult execute(ToolCall call, ToolContext context) {
        String prompt = call.getString("prompt");
        if (prompt == null || prompt.isBlank()) {
            return ToolResult.failure("缺少必要参数 prompt");
        }
        if (context.sessionId() == null) {
            return ToolResult.failure("缺少 sessionId");
        }
        try {
            SubagentRunner.SubagentResult result = runner.start(
                    context.sessionId(), prompt, context.delegationDepth(), call.getString("model", null));
            return ToolResult.success("子代理 " + result.childId() + " 已完成:\n" + result.content(),
                    Map.of("child_id", result.childId(), "content", result.content(), "depth", result.depth()));
        } catch (SubagentRunner.DepthExceededException e) {
            return ToolResult.failure("无法委托子代理: " + e.getMessage()
                    + " — 请自行完成该任务，或进一步拆分子任务。");
        }
    }
}
