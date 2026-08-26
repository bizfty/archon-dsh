package com.bizfty.anchon.dsh.schedule;

import com.bizfty.anchon.dsh.core.event.SessionEvent;
import com.bizfty.anchon.dsh.core.event.SessionEventBus;
import com.bizfty.anchon.dsh.core.event.SessionEventType;
import com.bizfty.anchon.dsh.core.model.MessageRole;
import com.bizfty.anchon.dsh.core.model.SessionId;
import com.bizfty.anchon.dsh.session.SessionService;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 提醒服务测试：延迟触发写入会话日志、列表、取消。
 */
class ScheduleServiceTest {

    private final SessionId sessionId = SessionId.of("sess_sched");

    @Test
    void reminderFiresAndWritesToSessionLog() {
        SessionService sessions = mock(SessionService.class);
        SessionEventBus bus = new SessionEventBus();
        List<SessionEvent> events = new ArrayList<>();
        bus.addListener(events::add);
        ScheduleService service = new ScheduleService(sessions, bus);

        ScheduleEntry entry = service.schedule(sessionId, 100, "记得提交代码");

        assertTrue(service.awaitFired(entry.id(), Duration.ofSeconds(5)), "提醒应在超时前触发");
        verify(sessions).append(org.mockito.ArgumentMatchers.eq(sessionId),
                org.mockito.ArgumentMatchers.eq(MessageRole.USER),
                org.mockito.ArgumentMatchers.eq("记得提交代码"), any(), any(), any());
        // 事件已发布（source=schedule）
        assertTrue(events.stream().anyMatch(e ->
                e.type() == SessionEventType.USER_MESSAGE && "schedule".equals(e.string("source"))));
    }

    @Test
    void listReturnsOnlyOwnedSchedules() {
        SessionService sessions = mock(SessionService.class);
        ScheduleService service = new ScheduleService(sessions, new SessionEventBus());
        service.schedule(sessionId, 60_000, "a");
        service.schedule(sessionId, 60_000, "b");
        service.schedule(SessionId.of("other"), 60_000, "c");
        assertEquals(2, service.list(sessionId).size());
    }

    @Test
    void cancelPreventsFiring() {
        SessionService sessions = mock(SessionService.class);
        ScheduleService service = new ScheduleService(sessions, new SessionEventBus());
        ScheduleEntry entry = service.schedule(sessionId, 100, "不该触发");
        service.cancel(entry.id());
        // 等待超过延迟，确认未触发（无 append 调用）
        try {
            Thread.sleep(300);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        verify(sessions, org.mockito.Mockito.never()).append(any(), any(), anyString(), any(), any(), any());
    }
}
