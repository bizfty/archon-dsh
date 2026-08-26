package com.bizfty.anchon.dsh.api.ws;

import com.bizfty.anchon.dsh.core.event.SessionEvent;
import com.bizfty.anchon.dsh.core.event.SessionEventBus;
import com.bizfty.anchon.dsh.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话事件 WebSocket 下行（对齐官方 events.mux 常驻流语义）。
 * <p>
 * 一条常驻连接接收全部会话事件；每个事件封装为下行帧：
 * <pre>
 *   { "type": "session", "sessionId": "...", "seq": 42, "event": { "eventType": "ASSISTANT_TOKEN", "data": {...} } }
 * </pre>
 * 帧格式与官方 RpcRequest 封装思路一致（事件名 + 载荷）；客户端按 sessionId 过滤渲染。
 * <p>
 * 事件总线是同步 observe-only 分发且监听器异常隔离（SessionEventBus.publish 已
 * try/catch 每个监听器），本 handler 抛错不会影响 agent loop 或其它监听器。
 */
@Component
public class SessionEventWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(SessionEventWebSocketHandler.class);

    private final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();
    private final JsonUtils jsonUtils;
    private final Runnable busDisposer;

    public SessionEventWebSocketHandler(SessionEventBus eventBus, JsonUtils jsonUtils) {
        this.jsonUtils = jsonUtils;
        // 注册总线监听：任何会话事件 → 广播给所有连接（由事件帧携带 sessionId 区分）
        this.busDisposer = eventBus.addListener(this::broadcast);
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.add(session);
        log.info("[WS] 连接建立: {}（当前 {} 个连接）", session.getId(), sessions.size());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session);
        log.info("[WS] 连接关闭: {}（剩余 {} 个）", session.getId(), sessions.size());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        sessions.remove(session);
        log.warn("[WS] 传输错误: {} — {}", session.getId(), exception.getMessage());
    }

    /** 会话事件 → 下行帧 → 广播所有连接。 */
    private void broadcast(SessionEvent event) {
        Map<String, Object> frame = Map.of(
                "type", "session",
                "sessionId", event.sessionId() == null ? "" : event.sessionId().value(),
                "seq", event.seq(),
                "event", Map.of("eventType", event.type().name(), "data", event.payload()));
        String json = jsonUtils.toJson(frame);
        for (WebSocketSession session : sessions) {
            try {
                synchronized (session) {
                    if (session.isOpen()) {
                        session.sendMessage(new TextMessage(json));
                    }
                }
            } catch (Exception e) {
                // 单个连接失败不影响其它连接；交由 close/transport-error 清理
                log.debug("[WS] 推送失败（连接可能已断）: {} — {}", session.getId(), e.getMessage());
                sessions.remove(session);
            }
        }
    }
}
