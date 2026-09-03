package com.bizfty.anchon.dsh.compaction;

import com.bizfty.anchon.dsh.core.event.SessionEvent;
import com.bizfty.anchon.dsh.core.event.SessionEventBus;
import com.bizfty.anchon.dsh.core.event.SessionEventType;
import com.bizfty.anchon.dsh.core.model.MessageRole;
import com.bizfty.anchon.dsh.core.model.SessionId;
import com.bizfty.anchon.dsh.core.model.SessionMessage;
import com.bizfty.anchon.dsh.llm.LlmGateway;
import com.bizfty.anchon.dsh.llm.ModelCallEventPayloads;
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

    @Test
    void emitsModelCallEventsForCompactionSummary() {
        SessionEventBus bus = new SessionEventBus();
        List<SessionEvent> events = new ArrayList<>();
        bus.addListener(events::add);
        CompactionService service = new CompactionService(
                new CompactionProperties(true, 1000, 10, 500), bus);
        List<SessionMessage> history = bigHistory(30, 200);

        CompactionService.CompressionPlan plan = service.compress(sessionId, history, new SummaryGateway("摘要内容"));

        assertEquals("摘要内容", plan.summaryText());
        SessionEvent req = events.stream()
                .filter(e -> e.type() == SessionEventType.MODEL_REQUEST).findFirst().orElseThrow();
        assertEquals(ModelCallEventPayloads.CALL_SITE_COMPACTION, req.payload().get("callSite"));
        assertFalse(req.payload().toString().contains("apiKey"));
        List<?> messages = (List<?>) req.payload().get("messages");
        assertTrue(messages.size() >= 2, "摘要请求应含 system + user 消息");
        SessionEvent res = events.stream()
                .filter(e -> e.type() == SessionEventType.MODEL_RESPONSE).findFirst().orElseThrow();
        assertEquals(ModelCallEventPayloads.CALL_SITE_COMPACTION, res.payload().get("callSite"));
        assertEquals("摘要内容", res.payload().get("text"));
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

    // ---- C-② 工具结果投影 pruner 对齐 ----

    private SessionMessage toolMsg(int i, String content) {
        return new SessionMessage("tool_" + i, sessionId, MessageRole.TOOL, content,
                "tc_" + i, "bash", null, i, Instant.now());
    }

    private ToolResultPruner pruner(int threshold, int head, int tail) {
        return new ToolResultPruner(new ToolResultPruneProperties(threshold, head, tail));
    }

    @Test
    void prunerAwarePressureSeesModelVisiblePrunedView() {
        // 12 条 × 8000 字符的 TOOL 结果：原文远超阈值，但模型可见（截断后）仅 ~109 字符/条
        List<SessionMessage> history = new ArrayList<>();
        String big = "y".repeat(8000);
        for (int i = 1; i <= 12; i++) {
            history.add(toolMsg(i, big));
        }
        CompactionProperties props = new CompactionProperties(true, 1000, 10, 500);

        CompactionService withoutPruner = new CompactionService(props, null, null);
        assertTrue(withoutPruner.needsCompaction(history), "无 pruner：原文估算应超阈值");

        CompactionService withPruner = new CompactionService(props, null, pruner(2000, 50, 20));
        assertFalse(withPruner.needsCompaction(history),
                "装配 pruner：压力按模型可见（截断）视图估算，不应触发压缩（对齐上游 remeasure 语义）");
    }

    @Test
    void projectedPruneReportCountsReplacedToolResults() {
        List<SessionMessage> history = new ArrayList<>();
        history.add(toolMsg(1, "y".repeat(8000)));
        history.add(toolMsg(2, "z".repeat(3000)));
        history.add(msg(3, "small user text")); // 非 TOOL 不参与
        history.add(toolMsg(4, "tiny"));        // 未超阈值不参与

        CompactionService service = new CompactionService(
                new CompactionProperties(true, 1000, 10, 500), null, pruner(2000, 50, 20));
        CompactionService.ToolResultPruneReport report = service.projectedPruneReport(history);

        assertEquals(2, report.replacedCount());
        long expectedSaved = ToolResultPruner.codePointLength("y".repeat(8000))
                - ToolResultPruner.codePointLength(pruner(2000, 50, 20).prune("y".repeat(8000)))
                + ToolResultPruner.codePointLength("z".repeat(3000))
                - ToolResultPruner.codePointLength(pruner(2000, 50, 20).prune("z".repeat(3000)));
        assertEquals(expectedSaved, report.savedCodePoints());
        assertTrue(report.savedCodePoints() > 0);
    }
}
