package com.bizfty.anchon.dsh.hostbridge.mux;

import org.springframework.context.annotation.Profile;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * /api/remote.mux WebSocket 薄壳：一个连接 = 一个 {@link MuxSession}。
 * 仅 {@code hostbridge} profile 激活时注册（见 MuxWebSocketConfig）。
 */
@Profile("hostbridge")
public class RemoteMuxWebSocketHandler extends TextWebSocketHandler {

    private final Map<String, MuxSession> sessions = new ConcurrentHashMap<>();
    private final String home;

    public RemoteMuxWebSocketHandler(String home) {
        this.home = home;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        MuxSession mux = new MuxSession(home, text -> sendText(session, text));
        sessions.put(session.getId(), mux);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        MuxSession mux = sessions.get(session.getId());
        if (mux == null) {
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }
        try {
            mux.receive(message.getPayload());
        } catch (StreamProtocol.MuxProtocolException e) {
            session.close(CloseStatus.POLICY_VIOLATION.withReason(e.getMessage()));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        MuxSession mux = sessions.remove(session.getId());
        if (mux != null) {
            mux.close();
        }
    }

    private void sendText(WebSocketSession session, String text) {
        try {
            if (session.isOpen()) {
                session.sendMessage(new TextMessage(text));
            }
        } catch (Exception e) {
            // 写失败：物理连接已不可用，交给 spring 关闭流程。
        }
    }
}
