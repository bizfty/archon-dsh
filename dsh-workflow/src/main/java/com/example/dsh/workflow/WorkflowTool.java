package com.example.dsh.workflow;

import com.example.dsh.code.CodeRunResult;
import com.example.dsh.code.CodeRuntimeService;
import com.example.dsh.tool.AgentTool;
import com.example.dsh.tool.ToolCall;
import com.example.dsh.tool.ToolContext;
import com.example.dsh.tool.ToolResult;
import com.example.dsh.tool.ToolSchema;
import com.example.dsh.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * `workflow` 工具 — 执行模型编写的 JS 编排脚本并返回脚本最终值（对应 DSH
 * workflow/tool-workflow）。脚本内可经 `await tools.<name>(args)` 扇出任意管线
 * 工具（subagent/send_message/list_agents 等），最终 `return` 值即工具结果。
 * <p>
 * 底层复用 code-runtime 的 Node.js 运行时（JSON-RPC 回环）；结果渲染有字符上限
 * （默认 50000，超长截断 + 提示，对应 DSH maxResultChars）。
 */
@Component
public class WorkflowTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(WorkflowTool.class);

    private final ObjectProvider<CodeRuntimeService> runtimeProvider;
    private final JsonUtils jsonUtils;
    private final int maxResultChars;

    /**
     * 经 {@link ObjectProvider} 懒解析 CodeRuntimeService（同 RunCodeTool）：
     * 其运行时链会在 ToolRegistry 构造期 `getBeansOfType(AgentTool)` 中形成环并
     * 被静默跳过；懒解析确保 workflow 被注册。
     */
    public WorkflowTool(ObjectProvider<CodeRuntimeService> runtimeProvider,
                        @Value("${dsh.workflow.max-result-chars:50000}") int maxResultChars) {
        this.runtimeProvider = runtimeProvider;
        this.jsonUtils = new JsonUtils();
        this.maxResultChars = maxResultChars;
    }

    @Override
    public String name() {
        return "workflow";
    }

    @Override
    public ToolSchema getSchema() {
        return ToolSchema.builder()
                .name(name())
                .description("运行一段 JS 编排脚本（扇出 subagent 等工具）并返回脚本最终值。"
                        + "脚本内用 await tools.<工具名>({参数}) 调用工具，用 return <值> 结束；"
                        + "可结合 subagent/send_message/list_agents 做多代理编排。")
                .addParameter("script", "string", "JS 脚本体（async 顶层 await）")
                .addParameter("description", "string", "脚本做什么的简短说明（可省略）")
                .addParameter("timeout_ms", "integer", "超时毫秒（默认 120000）")
                .required("script")
                .build();
    }

    @Override
    public ToolResult execute(ToolCall call, ToolContext context) {
        String script = call.getString("script");
        if (script == null || script.isBlank()) {
            return ToolResult.failure("workflow 需要 script 参数");
        }
        long timeoutMs = call.getInt("timeout_ms", 120_000);
        try {
            CodeRunResult result = runtimeProvider.getObject().run("js", script, context, timeoutMs);
            if (result.failed()) {
                return ToolResult.failure("workflow 执行失败: " + result.error());
            }
            String rendered = result.result() == null ? "" : jsonUtils.toJson(result.result());
            if (rendered.length() > maxResultChars) {
                rendered = rendered.substring(0, maxResultChars) + "…（结果已截断到 " + maxResultChars + " 字符）";
            }
            log.info("[Workflow] 脚本完成，结果 {} 字符", rendered.length());
            return ToolResult.success("workflow 结果: " + rendered);
        } catch (Exception e) {
            log.warn("[Workflow] 执行异常: {}", e.getMessage());
            return ToolResult.failure("workflow 执行异常: " + e.getMessage());
        }
    }
}
