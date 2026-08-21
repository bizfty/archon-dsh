package com.example.dsh.compaction;

import com.example.dsh.core.model.MessageRole;
import com.example.dsh.core.model.SessionId;
import com.example.dsh.core.model.SessionMessage;
import com.example.dsh.llm.LlmGateway;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 压缩服务测试：token 压力触发、摘要调用、失败回退、压缩计划形状。
 */
class CompactionServiceTest {

    private final SessionId sessionId = SessionId.of("sess_1");

    private SessionMessage msg(int i, String content) {
        return new SessionMessage("msg_" + i, sessionId, MessageRole.USER, content,
                null, null, null, i, Instant.now());
    }

    private List<SessionMessage> bigHistory(int n, int charsPerMsg) {
        List<SessionMessage> list = new ArrayList<>();
        String content = "x".repeat(charsPerMsg);
        for (int i = 1; i <= n; i++) {
            list.add(msg(i, content));
        }
        return list;
    }

    @Test
    void triggersOnTokenThreshold() {
        CompactionService service = new CompactionService(new CompactionProperties(true, 1000, 10, 500));
        // 30 条 × 200 字符 = 6000 字符 ≈ 1500 token > 1000
        List<SessionMessage> history = bigHistory(30, 200);
        assertTrue(service.needsCompaction(history));
        // 小历史不触发
        assertFalse(service.needsCompaction(bigHistory(5, 200)));
    }

    @Test
    void keepsTailAndCompressesHead() {
        CompactionService service = new CompactionService(new CompactionProperties(true, 1000, 10, 500));
        List<SessionMessage> history = bigHistory(30, 200);
        CompactionService.CompressionPlan plan = service.compress(history, new SummaryGateway("摘要内容"));

        assertEquals(20, plan.compressedCount());
        assertEquals(10, plan.tail().size());
        assertEquals("摘要内容", plan.summaryText());
        // 尾部保留原消息
        assertEquals("msg_21", plan.tail().get(0).id());
    }

    @Test
    void fallsBackToDeterministicSummaryWhenGatewayFails() {
        CompactionService service = new CompactionService(new CompactionProperties(true, 1000, 10, 500));
        List<SessionMessage> history = bigHistory(30, 200);
        CompactionService.CompressionPlan plan = service.compress(history, new FailingGateway());
        assertTrue(plan.summaryText().contains("历史已省略 20 条"));
    }

    @Test
    void summaryIsTrimmedToLimit() {
        CompactionService service = new CompactionService(new CompactionProperties(true, 1000, 10, 100));
        List<SessionMessage> history = bigHistory(30, 200);
        CompactionService.CompressionPlan plan = service.compress(history, new SummaryGateway("y".repeat(5000)));
        assertTrue(plan.summaryText().length() <= 101, "摘要应被截断到上限");
    }

    /** 摘要网关：返回固定摘要文本。 */
    private static final class SummaryGateway implements LlmGateway {
        private final String summary;

        SummaryGateway(String summary) {
            this.summary = summary;
        }

        @Override
        public ChatResponse call(List<Message> messages, ChatOptions options) {
            return new ChatResponse(List.of(new Generation(new org.springframework.ai.chat.messages.AssistantMessage(summary))));
        }

        @Override
        public Flux<ChatResponse> stream(List<Message> messages, ChatOptions options) {
            return Flux.just(call(messages, options));
        }

        @Override
        public String defaultModel() {
            return "deepseek-chat";
        }
    }

    /** 失败网关：抛异常触发回退。 */
    private static final class FailingGateway implements LlmGateway {
        @Override
        public ChatResponse call(List<Message> messages, ChatOptions options) {
            throw new IllegalStateException("mock failure");
        }

        @Override
        public Flux<ChatResponse> stream(List<Message> messages, ChatOptions options) {
            return Flux.error(new IllegalStateException("mock failure"));
        }

        @Override
        public String defaultModel() {
            return "deepseek-chat";
        }
    }
}
