package com.bizfty.anchon.dsh.tool;

import com.bizfty.anchon.dsh.util.JsonUtils;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.Map;
import java.util.UUID;

/**
 * AgentTool → Spring AI ToolCallback 适配器。
 * <p>
 * 把自定义工具系统接入 Spring AI 2.0 的 function calling：模型请求时
 * ChatModel 通过 ToolDefinition 声明工具，返回的工具调用由
 * AgentLoopService 手动调度进入 {@link ToolExecutionPipeline}，再经
 * 本适配器的 call() 执行（保持单一入口，事件/门控不旁路）。
 */
public class AgentToolCallback implements ToolCallback {

    private final AgentTool tool;
    private final ToolContext context;
    private final JsonUtils jsonUtils;
    private final ToolExecutionPipeline pipeline;

    public AgentToolCallback(AgentTool tool, ToolContext context, JsonUtils jsonUtils,
                             ToolExecutionPipeline pipeline) {
        this.tool = tool;
        this.context = context;
        this.jsonUtils = jsonUtils;
        this.pipeline = pipeline;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        String inputSchema = jsonUtils.toJson(tool.getSchema().inputSchema());
        return ToolDefinition.builder()
                .name(tool.name())
                .description(tool.getSchema().description())
                .inputSchema(inputSchema)
                .build();
    }

    @Override
    public String call(String toolInput) {
        ToolCall call = new ToolCall("call_" + UUID.randomUUID(), tool.name(),
                toolInput == null || toolInput.isBlank() ? Map.of() : jsonUtils.toMap(toolInput));
        ToolResult result = pipeline.execute(call.name(), toolInput, context);
        return jsonUtils.toJson(result.toMap());
    }

    @Override
    public String call(String toolInput, org.springframework.ai.chat.model.ToolContext toolContext) {
        return call(toolInput);
    }

    @Override
    public String toString() {
        return "AgentToolCallback(" + tool.name() + ")";
    }
}
