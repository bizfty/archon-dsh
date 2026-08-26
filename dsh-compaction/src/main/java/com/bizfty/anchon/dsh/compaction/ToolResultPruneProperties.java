package com.bizfty.anchon.dsh.compaction;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 工具结果截断配置（对应 DSH compaction/tool-result-pruner 的字符预算）。
 * <p>
 * 约束：head + marker + tail ≤ threshold；threshold 为正整数；head/tail 非负整数。
 */
@Component
public class ToolResultPruneProperties {

    private final int thresholdChars;
    private final int headChars;
    private final int tailChars;

    public ToolResultPruneProperties(
            @Value("${dsh.compaction.tool-result-threshold-chars:8192}") int thresholdChars,
            @Value("${dsh.compaction.tool-result-head-chars:4096}") int headChars,
            @Value("${dsh.compaction.tool-result-tail-chars:1024}") int tailChars) {
        if (thresholdChars <= 0) {
            throw new IllegalArgumentException("tool-result-threshold-chars 必须为正整数: " + thresholdChars);
        }
        if (headChars < 0 || tailChars < 0) {
            throw new IllegalArgumentException("head/tail chars 不能为负");
        }
        int emitted = headChars + ToolResultPruner.PRUNE_MARKER.length() + tailChars;
        if (emitted > thresholdChars) {
            throw new IllegalArgumentException(
                    "head+marker+tail (" + emitted + ") 不能超过 threshold (" + thresholdChars + ")");
        }
        this.thresholdChars = thresholdChars;
        this.headChars = headChars;
        this.tailChars = tailChars;
    }

    public int thresholdChars() {
        return thresholdChars;
    }

    public int headChars() {
        return headChars;
    }

    public int tailChars() {
        return tailChars;
    }
}
