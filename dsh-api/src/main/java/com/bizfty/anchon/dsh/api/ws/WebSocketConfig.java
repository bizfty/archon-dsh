package com.bizfty.anchon.dsh.api.ws;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * WebSocket 下行流配置（对齐官方 WebSocket downlink 语义）。
 * <p>
 * 注册 {@code /api/ws}：浏览器持有一条常驻下行连接，接收全部会话事件
 * （token/tool/question/approval/...）。上行（发送消息等）仍走 HTTP POST。
 * 断线由客户端指数退避重连；重连后经 REST 拉取消息列表 resync。
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final SessionEventWebSocketHandler eventHandler;

    public WebSocketConfig(SessionEventWebSocketHandler eventHandler) {
        this.eventHandler = eventHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(eventHandler, "/api/ws")
                .setAllowedOrigins("*");
    }
}
