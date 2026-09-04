package com.bizfty.anchon.dsh.hostbridge.mux;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * /api/remote.mux WS 注册（对齐官方 REMOTE_STREAM_MUX_PATH）。
 * 仅 {@code hostbridge} profile；真实接入时与 archon /api/ws 并存策略见 design-client-vue-a.md P5。
 */
@Configuration
@Profile("hostbridge")
@EnableWebSocket
public class MuxWebSocketConfig implements WebSocketConfigurer {

    private final RemoteMuxWebSocketHandler handler;

    public MuxWebSocketConfig(RemoteMuxWebSocketHandler handler) {
        this.handler = handler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, StreamProtocol.MUX_PATH)
                .setAllowedOrigins("*");
    }
}
