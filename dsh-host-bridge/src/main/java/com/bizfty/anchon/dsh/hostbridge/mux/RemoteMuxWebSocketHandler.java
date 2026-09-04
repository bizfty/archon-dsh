package com.bizfty.anchon.dsh.hostbridge.mux;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * /api/remote.mux WebSocket 薄壳：一个连接 = 一个 {@link MuxSession}，并登记进
 * {@link MuxSessionRegistry}（$events 事件广播用）。仅 {@code hostbridge} profile 激活。
 */
@Component
@Profile("hostbridge")
public class RemoteMuxWebSocketHandler extends TextWebSocketHandler {

    private final String home;
    private final MuxSessionRegistry registry;

    public RemoteMuxWebSocketHandler(@Value("${dsh.hostbridge.home:${user.home}}") String home,
                                     MuxSessionRegistry registry) {
        this.home = home;
        this.registry = registry;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        MuxSession mux = new MuxSession(home, text -> sendText(session, text));
        registry.register(session.getId(), mux);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        MuxSession mux = registry.lookup(session.getId());
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
        MuxSession mux = registry.lookup(session.getId());
        if (mux != null) {
            mux.close();
        }
        registry.unregister(session.getId());
    }

    private void sendText(WebSocketSession session, String text) {
        try {
            if (session.isOpen()) {
                session.sendMessage(new TextMessage(text));
            }
        } catch (Exception ignored) {
            // 写失败视为连接已死；closed 清理兜底
        }
    }
}
