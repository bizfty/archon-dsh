package com.example.dsh.feedback;

import com.example.dsh.core.event.SessionEvent;
import com.example.dsh.core.event.SessionEventBus;
import com.example.dsh.core.event.SessionEventType;
import com.example.dsh.core.model.SessionId;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 反馈服务测试：记录/列表/事件/校验。
 */
class FeedbackServiceTest {

    private final SessionId sessionId = SessionId.of("sess_fb");

    @Test
    void recordsAndListsFeedback() {
        SessionEventBus bus = new SessionEventBus();
        FeedbackService service = new FeedbackService(bus);
        FeedbackRecord record = service.record(sessionId, "msg_1", 5, "很棒");
        assertEquals("msg_1", record.messageId());
        assertEquals(1, service.listBySession(sessionId).size());
        assertEquals(1, service.listAll().size());
    }

    @Test
    void publishesFeedbackEvent() {
        SessionEventBus bus = new SessionEventBus();
        List<SessionEvent> events = new ArrayList<>();
        bus.addListener(events::add);
        FeedbackService service = new FeedbackService(bus);
        service.record(sessionId, "msg_2", 3, null);
        assertTrue(events.stream().anyMatch(e -> e.type() == SessionEventType.FEEDBACK));
        SessionEvent event = events.stream().filter(e -> e.type() == SessionEventType.FEEDBACK).findFirst().orElseThrow();
        assertEquals("msg_2", event.string("messageId"));
    }

    @Test
    void validatesInput() {
        FeedbackService service = new FeedbackService(new SessionEventBus());
        assertThrows(IllegalArgumentException.class,
                () -> service.record(sessionId, "", 5, null));
        assertThrows(IllegalArgumentException.class,
                () -> service.record(sessionId, "m", 9, null));
    }

    @Test
    void scopedBySession() {
        FeedbackService service = new FeedbackService(new SessionEventBus());
        service.record(sessionId, "a", 4, null);
        service.record(SessionId.of("other"), "b", 2, null);
        assertEquals(1, service.listBySession(sessionId).size());
        assertEquals(2, service.listAll().size());
    }
}
