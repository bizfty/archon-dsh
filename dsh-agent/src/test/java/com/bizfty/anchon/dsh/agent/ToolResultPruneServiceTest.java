package com.bizfty.anchon.dsh.agent;

import com.bizfty.anchon.dsh.compaction.CompactionProperties;
import com.bizfty.anchon.dsh.compaction.CompactionService;
import com.bizfty.anchon.dsh.compaction.ToolResultPruneProperties;
import com.bizfty.anchon.dsh.compaction.ToolResultPruner;
import com.bizfty.anchon.dsh.core.event.SessionEventBus;
import com.bizfty.anchon.dsh.core.event.SessionEventType;
import com.bizfty.anchon.dsh.core.model.MessageRole;
import com.bizfty.anchon.dsh.core.model.SessionId;
import com.bizfty.anchon.dsh.core.model.SessionMessage;
import com.bizfty.anchon.dsh.session.SessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * durable 工具结果修剪编排测试（P2-②）：开关关闭零操作；开启时选定行被标记、
 * TOOL_RESULT_PRUNE 事件发布、报告聚合正确。
 */
class ToolResultPruneServiceTest {

    private final SessionId sid = SessionId.of("sess_prune_svc");

    private CompactionService enabledCompaction() {
        return new CompactionService(
                new CompactionProperties(true, 1000, 10, 500, true), null,
                new ToolResultPruner(new ToolResultPruneProperties(100, 40, 20)));
    }

    private SessionMessage assistantCalls(int seq, String... toolCallIds) {
        String json = "[" + String.join(",", java.util.Arrays.stream(toolCallIds).map(id ->
                "{\"id\":\"" + id + "\",\"type\":\"function\",\"name\":\"bash\",\"arguments\":\"{}\"}").toList()) + "]";
        return new SessionMessage("a_" + seq, sid, MessageRole.ASSISTANT, "", null, null, json, seq, Instant.now());
    }

    private SessionMessage toolResult(int seq, String toolCallId, String content) {
        return new SessionMessage("t_" + seq, sid, MessageRole.TOOL, content, toolCallId, "bash", null, seq, Instant.now());
    }

    @Test
    void disabledSwitchReturnsZeroReportWithoutTouchingAnything() {
        SessionService sessionService = mock(SessionService.class);
        CompactionService disabled = new CompactionService(new CompactionProperties(true, 1000, 10, 500, false));
        ToolResultPruneService service = new ToolResultPruneService(sessionService, disabled, mock(SessionEventBus.class));

        CompactionService.ToolResultPruneReport report = service.pruneOldToolResults(sid);
        assertEquals(0, report.replacedCount());
        verify(sessionService, never()).listMessages(any());
    }

    @Test
    void marksAndPublishesForEachCandidate() {
        SessionService sessionService = mock(SessionService.class);
        List<SessionMessage> history = List.of(
                assistantCalls(1, "call_1"),
                toolResult(2, "call_1", "A".repeat(300)),
                toolResult(3, "call_9", "B".repeat(300))); // 孤立 → 不修剪
        when(sessionService.listMessages(sid)).thenReturn(history);
        when(sessionService.markToolResultPruned(eq(sid), any())).thenReturn(1);
        SessionEventBus eventBus = mock(SessionEventBus.class);
        ToolResultPruneService service = new ToolResultPruneService(sessionService, enabledCompaction(), eventBus);

        CompactionService.ToolResultPruneReport report = service.pruneOldToolResults(sid);

        assertEquals(1, report.replacedCount(), "只应修剪配对完整的超阈值 TOOL");
        assertTrue(report.savedCodePoints() > 0);
        verify(sessionService).markToolResultPruned(sid, "t_2");
        ArgumentCaptor<Map> payload = ArgumentCaptor.forClass(Map.class);
        verify(eventBus).publish(eq(sid), eq(SessionEventType.TOOL_RESULT_PRUNE), payload.capture());
        assertEquals("t_2", payload.getValue().get("messageId"));
        assertEquals("bash", payload.getValue().get("toolName"));
    }

    @Test
    void unparsableToolCallsJsonExcludedFromPairing() {
        SessionService sessionService = mock(SessionService.class);
        // assistant tool_calls JSON 格式损坏 → 不构成完整对 → 其 TOOL 不修剪
        List<SessionMessage> history = List.of(
                new SessionMessage("a_bad", sid, MessageRole.ASSISTANT, "", null, null,
                        "not-json{{", 1, Instant.now()),
                toolResult(2, "call_1", "A".repeat(300)));
        when(sessionService.listMessages(sid)).thenReturn(history);
        when(sessionService.markToolResultPruned(eq(sid), any())).thenReturn(1);
        ToolResultPruneService service = new ToolResultPruneService(sessionService, enabledCompaction(), mock(SessionEventBus.class));

        CompactionService.ToolResultPruneReport report = service.pruneOldToolResults(sid);
        assertEquals(0, report.replacedCount());
        verify(sessionService, never()).markToolResultPruned(any(), any());
    }
}
