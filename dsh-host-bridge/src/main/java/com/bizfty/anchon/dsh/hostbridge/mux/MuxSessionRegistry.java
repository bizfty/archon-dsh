package com.bizfty.anchon.dsh.hostbridge.mux;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 活跃 {@link MuxSession} 注册表：一个物理 WS 连接一个 MuxSession（见
 * RemoteMuxWebSocketHandler），此处收集全部在线会话供 $events 事件源广播
 * （对应用方 api-remotes 的 RemoteEventQueue → Gateway 转发，Java 侧等价物）。
 *
 * <p>仅 {@code hostbridge} profile 激活。事件桥实现（dsh-api 侧）构造时
 * 注入本注册表，业务写成功后 {@link #emitAll} 下行 {@code settings/document-updated}
 * 等 allowlist 事件。
 */
@Component
@Profile("hostbridge")
public final class MuxSessionRegistry {

    private final Map<String, MuxSession> sessions = new ConcurrentHashMap<>();

    /** WS 建立后登记（key = WebSocketSession.id）。 */
    public void register(String id, MuxSession session) {
        sessions.put(id, session);
    }

    /** WS 关闭后移除。 */
    public void unregister(String id) {
        sessions.remove(id);
    }

    /** 向全部就绪 $events 流广播一条 emit（不感知连接内部状态；幂等安全）。 */
    public void emitAll(String event, JsonNode argsArray) {
        for (MuxSession session : sessions.values()) {
            session.emit(event, argsArray);
        }
    }

    /** 按 WS 会话 id 取会话（handler 收帧用）。 */
    public MuxSession lookup(String id) {
        return sessions.get(id);
    }

    /** 在线会话数（测试/审计）。 */
    public int size() {
        return sessions.size();
    }
}
