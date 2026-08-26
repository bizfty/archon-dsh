package com.bizfty.anchon.dsh.tool;

/**
 * 工具结果后处理器 — 可检查/替换结果（对应 DSH tools/post-execute）。
 * <p>
 * 用途：结果决策（修剪、附加上下文、repeat 提醒、指标）。
 */
@FunctionalInterface
public interface ToolPostProcessor {

    ToolResult process(ToolCall call, ToolContext context, ToolResult result);

    /** 处理器顺序（越小越先）。 */
    default int order() {
        return 0;
    }
}
