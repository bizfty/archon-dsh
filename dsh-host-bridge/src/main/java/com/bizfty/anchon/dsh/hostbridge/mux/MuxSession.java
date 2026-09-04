package com.bizfty.anchon.dsh.hostbridge.mux;

import com.bizfty.anchon.dsh.hostbridge.json.Json;
import tools.jackson.databind.JsonNode;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 一个物理 WS 连接上的逻辑流多路复用会话（stream-server.ts RemoteStreamMuxConnection）。
 *
 * <p>输入 client 帧 → 输出 server 帧；逻辑流按 streamId 区分。保留端点 {@code $events}
 * 打开即发 ready（{@code clientId, host.home}），其后本会话可广播 emit；其余端点先经
 * opener 注册表（spike 内置 ping），未注册发 error 帧。
 */
public final class MuxSession {

    /** 逻辑流发射口（薄壳对接 spring WebSocketSession.sendMessage）。 */
    public interface Sink {
        void send(String text);
    }

    /** 一个逻辑流 opener：打开即同步产出（含 ready/错误帧），长流不发 end。 */
    public interface StreamOpener {
        void open(String streamId, JsonNode payload, Sink sink);
    }

    private final Map<String, StreamOpener> openers = new ConcurrentHashMap<>();
    private final Map<String, String> streams = new ConcurrentHashMap<>(); // streamId → endpoint
    private final Set<String> eventStreams = ConcurrentHashMap.newKeySet(); // 打开的 $events 流
    private final String clientId;
    private final String home;
    private final Sink sink;

    public MuxSession(String home, Sink sink) {
        this.clientId = UUID.randomUUID().toString();
        this.home = home;
        this.sink = sink;
        register(StreamProtocol.EVENTS_ENDPOINT, (streamId, payload, out) -> {
            eventStreams.add(streamId);
            out.send(StreamProtocol.item(streamId, StreamProtocol.readyValue(clientId, home)));
        });
        register("ping", (streamId, payload, out) -> {
            out.send(StreamProtocol.item(streamId, Json.object().put("pong", true)));
            out.send(StreamProtocol.end(streamId));
        });
    }

    public String clientId() {
        return clientId;
    }

    /** 注册自定义逻辑流 opener（P4 各 Remote 流经此接入）。 */
    public void register(String endpoint, StreamOpener opener) {
        if (openers.putIfAbsent(endpoint, opener) != null) {
            throw new IllegalStateException("hostbridge: duplicate stream endpoint " + endpoint);
        }
    }

    /** 处理一条 client 文本帧；帧非法抛 {@link StreamProtocol.MuxProtocolException}。 */
    public void receive(String text) {
        StreamProtocol.ClientFrame frame = StreamProtocol.parseClientFrame(text);
        if (frame instanceof StreamProtocol.CancelFrame cancel) {
            streams.remove(cancel.streamId());
            eventStreams.remove(cancel.streamId());
            return;
        }
        StreamProtocol.OpenFrame open = (StreamProtocol.OpenFrame) frame;
        if (streams.putIfAbsent(open.streamId(), open.endpoint()) != null) {
            throw new IllegalStateException("api gateway: duplicate Remote stream id " + open.streamId());
        }
        StreamOpener opener = openers.get(open.endpoint());
        if (opener == null) {
            streams.remove(open.streamId());
            sink.send(StreamProtocol.error(open.streamId(), "gateway/lookup-not-found",
                    "no Remote stream opener for " + open.endpoint()));
            return;
        }
        try {
            opener.open(open.streamId(), open.payload(), sink);
        } catch (RuntimeException e) {
            streams.remove(open.streamId());
            eventStreams.remove(open.streamId());
            sink.send(StreamProtocol.error(open.streamId(), "gateway/internal", String.valueOf(e.getMessage())));
        }
    }

    /** 向所有已就绪的 $events 逻辑流广播一条 emit（P3 事件桥接入口）。 */
    public void emit(String event, JsonNode argsArray) {
        for (String streamId : eventStreams) {
            sink.send(StreamProtocol.item(streamId, StreamProtocol.emitValue(event, argsArray)));
        }
    }

    /** 连接关闭：清空全部逻辑流。 */
    public void close() {
        streams.clear();
        eventStreams.clear();
    }
}
