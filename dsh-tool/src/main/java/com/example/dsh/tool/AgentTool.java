package com.example.dsh.tool;

/**
 * 工具接口 — 实现类通过 {@link Tool} 注解声明名称与描述，由 ToolRegistry 自动注册。
 * <p>
 * 对应 DSH ToolDefinition 的执行面（execute(args, exec)）；参数校验与输出
 * 序列化由 ToolExecutionPipeline 统一处理。
 */
public interface AgentTool {

    /** 工具名称（与 @Tool.name 一致）。 */
    String name();

    /** 工具参数 Schema（注入模型 function calling 声明）。 */
    ToolSchema getSchema();

    /** 执行工具调用。 */
    ToolResult execute(ToolCall call, ToolContext context);

    /** 是否需要人工审批（从 @Tool 注解读取，可覆写）。 */
    default boolean requiresApproval() {
        Tool annotation = getClass().getAnnotation(Tool.class);
        return annotation != null && annotation.requiresApproval();
    }

    /** 执行超时毫秒（从 @Tool 注解读取，可覆写；0=不限）。 */
    default long timeoutMs() {
        Tool annotation = getClass().getAnnotation(Tool.class);
        return annotation == null ? 0 : annotation.timeoutMs();
    }

    /**
     * 是否可并发安全执行（对应 DSH isConcurrencySafe）。
     * <p>
     * 默认 false（串行屏障）。仅当工具不共享可变状态、并发调用可交换时才覆写为 true；
     * 模型一次返回多个调用时，连续 safe 调用进入有界滚动池并行，其余保持串行。
     */
    default boolean isConcurrencySafe() {
        return false;
    }
}
