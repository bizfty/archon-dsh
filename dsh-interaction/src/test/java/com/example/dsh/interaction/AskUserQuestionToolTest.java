package com.example.dsh.interaction;

import com.example.dsh.tool.ToolCall;
import com.example.dsh.tool.ToolContext;
import com.example.dsh.tool.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ask_user_question 工具测试：应答者回答 / 无应答者结构化失败。
 */
class AskUserQuestionToolTest {

    @Test
    void returnsUserAnswer() {
        InMemoryUserQuestionProvider provider = new InMemoryUserQuestionProvider();
        AskUserQuestionTool tool = new AskUserQuestionTool(new UserQuestionService(
                objectProviderOf(provider), 5000), new com.example.dsh.core.event.SessionEventBus());

        // 预填应答（在工具调用前挂起问题并回答）
        Thread answerer = Thread.startVirtualThread(() -> {
            while (provider.pendingQuestions().isEmpty()) {
                Thread.onSpinWait();
            }
            provider.answer(provider.pendingQuestions().get(0).id(), "是的");
        });

        ToolResult result = tool.execute(new ToolCall("call_1", "ask_user_question",
                Map.of("question", "继续吗？")), ToolContext.builder().build());

        assertTrue(result.success());
        assertEquals("是的", result.data().get("answer"));
    }

    @Test
    void failsStructuredWithoutProvider() {
        AskUserQuestionTool tool = new AskUserQuestionTool(new UserQuestionService(
                objectProviderOf(), 100), new com.example.dsh.core.event.SessionEventBus());
        ToolResult result = tool.execute(new ToolCall("call_1", "ask_user_question",
                Map.of("question", "继续吗？")), ToolContext.builder().build());
        assertEquals(false, result.success());
        assertTrue(result.message().contains("无法向用户提问"));
    }

    @SuppressWarnings("unchecked")
    private org.springframework.beans.factory.ObjectProvider<UserQuestionProvider> objectProviderOf(
            UserQuestionProvider... providers) {
        org.springframework.beans.factory.ObjectProvider<UserQuestionProvider> op =
                org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);
        org.mockito.Mockito.when(op.orderedStream()).thenReturn(java.util.stream.Stream.of(providers));
        return op;
    }
}
