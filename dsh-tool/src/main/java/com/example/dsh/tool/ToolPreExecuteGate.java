package com.example.dsh.tool;

import java.util.Optional;

/**
 * 工具执行前门 — allow/deny/ask 决策面（对应 DSH tools/pre-execute）。
 * <p>
 * 返回拒绝理由则调用被拒（模型看到结构化失败）；返回 empty 放行。
 * 注意：与 DSH 一致，deny 是单调的 — 后续门不能把前面的拒绝翻成放行。
 */
@FunctionalInterface
public interface ToolPreExecuteGate {

    /** 检查调用；返回拒绝理由或 empty。 */
    Optional<String> check(ToolCall call, ToolContext context);

    /** 门顺序（越小越先）。 */
    default int order() {
        return 0;
    }
}
