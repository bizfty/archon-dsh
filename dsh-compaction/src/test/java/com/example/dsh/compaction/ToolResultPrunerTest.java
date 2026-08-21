package com.example.dsh.compaction;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具结果截断器测试：阈值内原样、超阈值头+标记+尾、码点安全、配置校验。
 */
class ToolResultPrunerTest {

    private ToolResultPruner pruner(int threshold, int head, int tail) {
        return new ToolResultPruner(new ToolResultPruneProperties(threshold, head, tail));
    }

    private ToolResultPruner defaultPruner() {
        return pruner(8192, 4096, 1024);
    }

    @Test
    void smallContentUnchanged() {
        assertEquals("hello", defaultPruner().prune("hello"));
        assertFalse(defaultPruner().needsPruning("hello"));
    }

    @Test
    void boundaryExactThresholdUnchanged() {
        String content = "x".repeat(8192);
        assertEquals(content, defaultPruner().prune(content));
        assertFalse(defaultPruner().needsPruning(content));
    }

    @Test
    void oversizedPrunedToHeadMarkerTail() {
        String content = "A".repeat(100) + "B".repeat(9000) + "C".repeat(100);
        String pruned = defaultPruner().prune(content);
        assertTrue(pruned.startsWith("A".repeat(100)), "保留头");
        assertTrue(pruned.contains(ToolResultPruner.PRUNE_MARKER), "含截断标记");
        assertTrue(pruned.endsWith("C".repeat(100)), "保留尾");
        assertTrue(defaultPruner().needsPruning(content));
        // 长度 = head + marker + tail
        assertEquals(4096 + ToolResultPruner.PRUNE_MARKER.length() + 1024, pruned.length());
    }

    @Test
    void nullHandled() {
        assertNull(defaultPruner().prune(null));
        assertFalse(defaultPruner().needsPruning(null));
    }

    @Test
    void codePointSafeWithSurrogates() {
        // 表情符号是代理对：截断不得拆对
        String emoji = "😀".repeat(100); // 100 码点 = 200 UTF-16 units
        assertEquals(100, ToolResultPruner.codePointLength(emoji));
        String pruned = pruner(100, 20, 10).prune(emoji + "TAIL");
        assertTrue(pruned.startsWith("😀".repeat(20)), "头按码点截断，不拆代理对");
        assertTrue(pruned.endsWith("TAIL"));
        // 码点长度 = head 20 + marker + tail 10（4 字母 + 6 表情）
        assertEquals(20 + ToolResultPruner.codePointLength(ToolResultPruner.PRUNE_MARKER) + 10,
                ToolResultPruner.codePointLength(pruned));
    }

    @Test
    void tailLongerThanRemainingKeepsAll() {
        // 内容略超阈值：tail 吃掉剩余部分
        String content = "X".repeat(9000) + "END";
        String pruned = pruner(9000, 100, 8192).prune(content);
        assertTrue(pruned.contains("END"), "尾完整保留");
        assertTrue(pruned.contains(ToolResultPruner.PRUNE_MARKER));
    }

    @Test
    void configValidation() {
        assertThrows(IllegalArgumentException.class, () -> new ToolResultPruneProperties(0, 0, 0),
                "threshold 必须为正");
        assertThrows(IllegalArgumentException.class, () -> new ToolResultPruneProperties(100, -1, 0),
                "head 不能为负");
        assertThrows(IllegalArgumentException.class, () -> new ToolResultPruneProperties(100, 90, 90),
                "head+marker+tail 不能超过 threshold");
    }
}
