package com.bizfty.anchon.dsh.hostbridge.mux;

import com.bizfty.anchon.dsh.hostbridge.json.Json;
import com.bizfty.anchon.dsh.hostbridge.rpc.RpcCodec;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.Set;

/**
 * remote.mux WebSocket 帧协议编解码（gateway/stream-protocol.ts）。
 *
 * <p>client→server：{@code {type:'open',streamId,endpoint,payload}} |
 * {@code {type:'cancel',streamId}}；
 * server→client：{@code {type:'item',streamId,value?}} | {@code {type:'end',streamId}}
 * | {@code {type:'error',streamId,error:{code,message,details}}}。
 * 常量与保留端点取自官方 stream-protocol.ts。
 */
public final class StreamProtocol {

    public static final String MUX_PATH = "/api/remote.mux";
    public static final String EVENTS_ENDPOINT = "$events";
    public static final String EVENT_RESULT_ENDPOINT = "$events/result";

    private static final Set<String> OPEN_KEYS = Set.of("type", "streamId", "endpoint", "payload");
    private static final Set<String> CANCEL_KEYS = Set.of("type", "streamId");
    private static final Set<String> ITEM_KEYS_OPTIONAL = Set.of("type", "streamId", "value");
    private static final Set<String> END_KEYS = Set.of("type", "streamId");
    private static final Set<String> ERROR_KEYS = Set.of("type", "streamId", "error");
    private static final Set<String> FAILURE_KEYS = Set.of("code", "message", "details");

    private StreamProtocol() {
    }

    /** client 帧之一。 */
    public sealed interface ClientFrame permits OpenFrame, CancelFrame {
    }

    /** open 逻辑流。 */
    public record OpenFrame(String streamId, String endpoint, JsonNode payload) implements ClientFrame {
    }

    /** cancel 逻辑流。 */
    public record CancelFrame(String streamId) implements ClientFrame {
    }

    /**
     * 解析一条 client 文本帧（exact-key）。
     *
     * @throws MuxProtocolException 帧非法
     */
    public static ClientFrame parseClientFrame(String text) {
        ObjectNode node = Json.parseObject(text);
        if (node == null) {
            throw new MuxProtocolException("api gateway: Remote stream message is not JSON");
        }
        String type = node.path("type").asText();
        if ("cancel".equals(type) && RpcCodec.exactKeys(node, CANCEL_KEYS) && validId(node.path("streamId").asText())) {
            return new CancelFrame(node.path("streamId").asText());
        }
        if ("open".equals(type) && RpcCodec.exactKeys(node, OPEN_KEYS)
                && validId(node.path("streamId").asText())
                && !node.path("endpoint").asText().isEmpty()) {
            return new OpenFrame(node.path("streamId").asText(), node.path("endpoint").asText(), node.get("payload"));
        }
        throw new MuxProtocolException("api gateway: invalid Remote stream client message");
    }

    /** item 帧（value 可为 null/缺省）。 */
    public static String item(String streamId, JsonNode value) {
        ObjectNode frame = Json.object();
        frame.put("type", "item");
        frame.put("streamId", streamId);
        if (value != null) {
            frame.set("value", value);
        }
        return Json.write(frame);
    }

    /** end 帧。 */
    public static String end(String streamId) {
        ObjectNode frame = Json.object();
        frame.put("type", "end");
        frame.put("streamId", streamId);
        return Json.write(frame);
    }

    /** error 帧。 */
    public static String error(String streamId, String code, String message) {
        ObjectNode frame = Json.object();
        frame.put("type", "error");
        frame.put("streamId", streamId);
        ObjectNode failure = Json.object();
        failure.put("code", code);
        failure.put("message", message);
        failure.set("details", Json.object());
        frame.set("error", failure);
        return Json.write(frame);
    }

    /** $events 打开即发的 ready 帧 value：{type:'ready', clientId, host:{home}}。 */
    public static JsonNode readyValue(String clientId, String home) {
        ObjectNode frame = Json.object();
        frame.put("type", "ready");
        frame.put("clientId", clientId);
        ObjectNode host = Json.object();
        host.put("home", home);
        frame.set("host", host);
        return frame;
    }

    /** $events emit 帧 value：{type:'emit', event, args:[...]}。 */
    public static JsonNode emitValue(String event, JsonNode argsArray) {
        ObjectNode frame = Json.object();
        frame.put("type", "emit");
        frame.put("event", event);
        frame.set("args", argsArray == null ? Json.object() : argsArray);
        return frame;
    }

    private static boolean validId(String id) {
        return id != null && !id.isEmpty();
    }

    /** 帧协议非法信号。 */
    public static final class MuxProtocolException extends RuntimeException {
        public MuxProtocolException(String message) {
            super(message);
        }
    }
}
