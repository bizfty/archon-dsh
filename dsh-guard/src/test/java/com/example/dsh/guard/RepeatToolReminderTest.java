package com.example.dsh.guard;

import com.example.dsh.core.model.SessionId;
import com.example.dsh.tool.ToolCall;
import com.example.dsh.tool.ToolContext;
import com.example.dsh.tool.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 重复调用提醒测试：连续相同 (tool, args) 达到阈值后注入提醒上下文。
 */
class RepeatToolReminderTest {

    private final SessionId sessionId = SessionId.of("sess_1");
    private final RepeatToolReminder reminder = new RepeatToolReminder(3);

    private ToolResult process(int callNo) {
        ToolCall call = new ToolCall("call_" + callNo, "bash",
                Map.of("command", "pwd"));
        ToolResult result = ToolResult.failure("exit 1");
        return reminder.process(call, ToolContext.builder().sessionId(sessionId).build(), result);
    }

    @Test
    void remindsAfterThresholdRepeatedCalls() {
        assertTrue(process(1).additionalContexts().isEmpty());
        assertTrue(process(2).additionalContexts().isEmpty());
        ToolResult third = process(3);
        assertFalse(third.additionalContexts().isEmpty(), "第 3 次相同调用应触发提醒");
        assertTrue(third.additionalContexts().get(0).contains("连续第 3 次"));
        // 提醒不替换结果内容
        assertEquals("exit 1", third.message());
    }

    @Test
    void differentArgsResetChain() {
        process(1);
        process(2);
        // 换参数 → 新链
        ToolCall other = new ToolCall("call_3", "bash", Map.of("command", "ls"));
        ToolResult result = reminder.process(other,
                ToolContext.builder().sessionId(sessionId).build(), ToolResult.failure("x"));
        assertTrue(result.additionalContexts().isEmpty());
    }

    @Test
    void chainsArePerSession() {
        process(1);
        process(2);
        ToolResult otherSession = reminder.process(
                new ToolCall("call_3", "bash", Map.of("command", "pwd")),
                ToolContext.builder().sessionId(SessionId.of("sess_other")).build(),
                ToolResult.failure("x"));
        assertTrue(otherSession.additionalContexts().isEmpty(), "不同会话不应共享链");
    }
}
