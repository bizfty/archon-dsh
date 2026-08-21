package com.example.dsh.compaction;

import org.springframework.stereotype.Component;

/**
 * 工具结果截断器（对应 DSH compaction/tool-result-pruner）。
 * <p>
 * **回放安全、无模型**：超阈值的大工具结果在投影（surface）层截断为
 * 头 + 标记 + 尾，会话日志保留完整原文 — 与压缩的"表面替换不删日志"一致；
 * 重放/重启不受影响（确定性截断）。
 * <p>
 * 语义：内容长度（Unicode 码点）≤ threshold 原样返回；否则保留头 headChars
 * 与尾 tailChars，中间用固定标记替换。
 */
@Component
public class ToolResultPruner {

    /** 被移除中间段的固定标记。 */
    public static final String PRUNE_MARKER = "\n\n[... tool result middle pruned ...]\n\n";

    private final ToolResultPruneProperties properties;

    public ToolResultPruner(ToolResultPruneProperties properties) {
        this.properties = properties;
    }

    /** 是否需要截断（超阈值）。 */
    public boolean needsPruning(String content) {
        return content != null && codePointLength(content) > properties.thresholdChars();
    }

    /** 截断：超阈值 → 头 + 标记 + 尾；否则原样返回（null → null）。 */
    public String prune(String content) {
        if (content == null) {
            return null;
        }
        int length = codePointLength(content);
        if (length <= properties.thresholdChars()) {
            return content;
        }
        int headChars = Math.min(properties.headChars(), length);
        int tailChars = Math.min(properties.tailChars(), length - headChars);
        String head = substringByCodePoints(content, 0, headChars);
        String tail = substringByCodePoints(content, length - tailChars, length);
        return head + PRUNE_MARKER + tail;
    }

    /** Unicode 码点计数（不拆代理对；对应 DSH codePointLength）。 */
    public static int codePointLength(String text) {
        return text.codePointCount(0, text.length());
    }

    private static String substringByCodePoints(String text, int from, int to) {
        int[] cp = text.codePoints().toArray();
        StringBuilder sb = new StringBuilder();
        for (int i = Math.max(0, from); i < Math.min(cp.length, to); i++) {
            sb.appendCodePoint(cp[i]);
        }
        return sb.toString();
    }
}
