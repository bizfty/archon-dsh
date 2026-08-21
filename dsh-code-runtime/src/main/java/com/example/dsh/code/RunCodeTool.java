package com.example.dsh.code;

import com.example.dsh.tool.AgentTool;
import com.example.dsh.tool.Tool;
import com.example.dsh.tool.ToolCall;
import com.example.dsh.tool.ToolContext;
import com.example.dsh.tool.ToolResult;
import com.example.dsh.tool.ToolSchema;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Map;

/**
 * run_code 工具 — 执行模型编写的 JS 程序（对应 DSH Code Mode 的 run_code 传输）。
 * <p>
 * 程序内经 {@code await tools.工具名(args)} 调用全部可用工具；
 * console.log 捕获为日志，return 值为结果。状态每次全新（fresh per run）。
 */
@Tool(name = "run_code",
      description = "执行一段 JavaScript 程序。程序内用 await tools.工具名(参数) 调用可用工具，"
              + "console.log 输出日志，return 返回结果。适合需要多步计算/多次工具调用的任务。")
public class RunCodeTool implements AgentTool {

    private final ObjectProvider<CodeRuntimeService> runtimeProvider;

    /**
     * 经 {@link ObjectProvider} 懒解析 CodeRuntimeService：其运行时链
     * （NodeCodeRuntime → ToolExecutionPipeline → ToolRegistry）在 ToolRegistry
     * 构造期 `getBeansOfType(AgentTool)` 中会形成环并被静默跳过；
     * 懒解析可打破该环，确保 run_code 被注册（同 SubagentRunner 模式）。
     */
    public RunCodeTool(ObjectProvider<CodeRuntimeService> runtimeProvider) {
        this.runtimeProvider = runtimeProvider;
    }

    @Override
    public String name() {
        return "run_code";
    }

    @Override
    public ToolSchema getSchema() {
        return ToolSchema.builder()
                .name(name())
                .description("执行 JS 或 Python 程序并调用工具。")
                .addParameter("language", "string", "js（默认）| python")
                .addParameter("code", "string", "程序体（JS: await tools.name(args)；Python: await tools.name(args)）")
                .addParameter("description", "string", "程序做什么的简短说明")
                .addParameter("timeout_ms", "integer", "超时毫秒（默认 60000）")
                .required("code", "description")
                .build();
    }

    @Override
    public ToolResult execute(ToolCall call, ToolContext context) {
        String code = call.getString("code");
        if (code == null || code.isBlank()) {
            return ToolResult.failure("缺少必要参数 code");
        }
        String language = call.getString("language", "js");
        int timeoutMs = call.getInt("timeout_ms", 0);
        CodeRunResult result;
        try {
            result = runtimeProvider.getObject().run(language, code, context, timeoutMs);
        } catch (IllegalArgumentException e) {
            return ToolResult.failure(e.getMessage());
        }
        StringBuilder sb = new StringBuilder();
        if (!result.logs().isEmpty()) {
            sb.append("日志:\n");
            for (String logLine : result.logs()) {
                sb.append("  ").append(logLine).append('\n');
            }
        }
        if (result.failed()) {
            return ToolResult.failure("run_code 失败: " + result.error() + "\n" + sb);
        }
        sb.append("结果: ").append(result.result());
        return ToolResult.success(sb.toString(), Map.of(
                "logs", result.logs(),
                "result", result.result()));
    }
}
