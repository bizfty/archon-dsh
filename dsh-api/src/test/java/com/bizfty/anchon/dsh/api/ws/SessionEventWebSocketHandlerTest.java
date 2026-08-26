package com.bizfty.anchon.dsh.api.ws;

import com.bizfty.anchon.dsh.core.event.SessionEventBus;
import com.bizfty.anchon.dsh.core.event.SessionEventType;
import com.bizfty.anchon.dsh.core.model.SessionId;
import com.bizfty.anchon.dsh.util.JsonUtils;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * SessionEventWebSocketHandler 测试：总线事件 → 下行帧广播、
 * 连接开/关管理、单个连接失败不影响其它。
 */
class SessionEventWebSocketHandlerTest {

    private final JsonUtils jsonUtils = new JsonUtils();

    private WebSocketSession mockSession(String id, boolean open, List<TextMessage> outbox) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        when(session.isOpen()).thenReturn(open);
        try {
            org.mockito.Mockito.doAnswer(inv -> {
                outbox.add(inv.getArgument(0));
                return null;
            }).when(session).sendMessage(org.mockito.ArgumentMatchers.any(TextMessage.class));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return session;
    }

    @Test
    void busEventIsBroadcastAsFrame() {
        SessionEventBus bus = new SessionEventBus();
        SessionEventWebSocketHandler handler = new SessionEventWebSocketHandler(bus, jsonUtils);
        List<TextMessage> outbox = new ArrayList<>();
        WebSocketSession session = mockSession("ws-1", true, outbox);
        handler.afterConnectionEstablished(session);

        bus.publish(SessionId.of("sess_a"), SessionEventType.ASSISTANT_TOKEN,
                Map.of("content", "你好"));

        assertEquals(1, outbox.size(), "事件应推给连接");
        String frame = outbox.get(0).getPayload();
        assertTrue(frame.contains("\"type\":\"session\""), "帧应含 type=session: " + frame);
        assertTrue(frame.contains("sess_a"), "帧应含 sessionId: " + frame);
        assertTrue(frame.contains("ASSISTANT_TOKEN"), "帧应含事件类型: " + frame);
        assertTrue(frame.contains("你好"), "帧应含载荷: " + frame);
    }

    @Test
    void closedOrFailedSessionDoesNotBlockOthers() throws Exception {
        SessionEventBus bus = new SessionEventBus();
        SessionEventWebSocketHandler handler = new SessionEventWebSocketHandler(bus, jsonUtils);

        // 第一个连接：sendMessage 抛异常（模拟已断但未标记 closed）
        WebSocketSession broken = mock(WebSocketSession.class);
        when(broken.getId()).thenReturn("ws-broken");
        when(broken.isOpen()).thenReturn(true);
        org.mockito.Mockito.doThrow(new RuntimeException("连接已断"))
                .when(broken).sendMessage(org.mockito.ArgumentMatchers.any(TextMessage.class));
        handler.afterConnectionEstablished(broken);

        List<TextMessage> outbox = new ArrayList<>();
        WebSocketSession good = mockSession("ws-good", true, outbox);
        handler.afterConnectionEstablished(good);

        bus.publish(SessionId.of("sess_b"), SessionEventType.TOOL_RESULT, Map.of("tool", "bash"));

        assertEquals(1, outbox.size(), "健康连接仍应收到事件");
        assertTrue(outbox.get(0).getPayload().contains("sess_b"));
    }

    @Test
    void connectionCloseRemovesSession() {
        SessionEventBus bus = new SessionEventBus();
        SessionEventWebSocketHandler handler = new SessionEventWebSocketHandler(bus, jsonUtils);
        List<TextMessage> outbox = new ArrayList<>();
        WebSocketSession session = mockSession("ws-2", true, outbox);
        handler.afterConnectionEstablished(session);
        handler.afterConnectionClosed(session, CloseStatus.NORMAL);

        bus.publish(SessionId.of("sess_c"), SessionEventType.USER_MESSAGE, Map.of("content", "x"));
        assertEquals(0, outbox.size(), "关闭后的连接不应再收到事件");
    }
}
