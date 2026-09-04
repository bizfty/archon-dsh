package com.bizfty.anchon.dsh.hostbridge.mux;

import com.bizfty.anchon.dsh.hostbridge.json.Json;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * remote.mux 帧协议编解码测试：open/cancel 解析、非法帧拒绝、服务帧渲染精确形状。
 */
class StreamProtocolTest {

    @Test
    void parsesOpenFrame() {
        StreamProtocol.ClientFrame frame = StreamProtocol.parseClientFrame(
                "{\"type\":\"open\",\"streamId\":\"s1\",\"endpoint\":\"$events\",\"payload\":{\"args\":{}}}");
        assertTrue(frame instanceof StreamProtocol.OpenFrame);
        StreamProtocol.OpenFrame open = (StreamProtocol.OpenFrame) frame;
        assertEquals("s1", open.streamId());
        assertEquals("$events", open.endpoint());
        assertEquals(true, open.payload().path("args").isObject());
    }

    @Test
    void parsesCancelFrame() {
        StreamProtocol.ClientFrame frame = StreamProtocol.parseClientFrame(
                "{\"type\":\"cancel\",\"streamId\":\"s1\"}");
        assertTrue(frame instanceof StreamProtocol.CancelFrame);
        assertEquals("s1", ((StreamProtocol.CancelFrame) frame).streamId());
    }

    @Test
    void rejectsInvalidFrames() {
        // 非 JSON
        assertThrows(StreamProtocol.MuxProtocolException.class, () -> StreamProtocol.parseClientFrame("nope"));
        // 未知 type
        assertThrows(StreamProtocol.MuxProtocolException.class, () ->
                StreamProtocol.parseClientFrame("{\"type\":\"nope\",\"streamId\":\"s1\"}"));
        // open 缺 payload / 空 streamId
        assertThrows(StreamProtocol.MuxProtocolException.class, () ->
                StreamProtocol.parseClientFrame("{\"type\":\"open\",\"streamId\":\"\",\"endpoint\":\"e\",\"payload\":{}}"));
        // 多余字段
        assertThrows(StreamProtocol.MuxProtocolException.class, () ->
                StreamProtocol.parseClientFrame("{\"type\":\"cancel\",\"streamId\":\"s1\",\"extra\":1}"));
    }

    @Test
    void rendersServerFrames() {
        assertEquals("{\"type\":\"item\",\"streamId\":\"s1\",\"value\":{\"pong\":true}}",
                StreamProtocol.item("s1", Json.object().put("pong", true)));
        assertEquals("{\"type\":\"end\",\"streamId\":\"s1\"}", StreamProtocol.end("s1"));
        var error = Json.parseObject(StreamProtocol.error("s1", "gateway/lookup-not-found", "missing"));
        assertEquals("error", error.path("type").asText());
        assertEquals("gateway/lookup-not-found", error.path("error").path("code").asText());
    }

    @Test
    void rendersReadyValueWithHostInfo() {
        var ready = StreamProtocol.readyValue("c-1", "/home/u");
        assertEquals("ready", ready.path("type").asText());
        assertEquals("c-1", ready.path("clientId").asText());
        assertEquals("/home/u", ready.path("host").path("home").asText());
    }
}
