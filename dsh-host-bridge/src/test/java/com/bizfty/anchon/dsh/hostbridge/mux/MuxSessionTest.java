package com.bizfty.anchon.dsh.hostbridge.mux;

import com.bizfty.anchon.dsh.hostbridge.json.Json;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MuxSession 逻辑流状态机测试：$events ready、emit 广播、ping 生命周期、
 * 未知 endpoint 错误帧、cancel、重复 open 拒绝。
 */
class MuxSessionTest {

    private static final class RecordingSink implements MuxSession.Sink {
        final List<String> frames = new ArrayList<>();

        @Override
        public void send(String text) {
            frames.add(text);
        }
    }

    private JsonNode itemValue(RecordingSink sink, int index) {
        return Json.parseObject(sink.frames.get(index)).path("value");
    }

    @Test
    void openingEventsStreamReceivesReadyItem() {
        RecordingSink sink = new RecordingSink();
        MuxSession session = new MuxSession("/home/u", sink);
        session.receive("{\"type\":\"open\",\"streamId\":\"e1\",\"endpoint\":\"$events\",\"payload\":{\"args\":{}}}");
        assertEquals(1, sink.frames.size());
        JsonNode item = Json.parseObject(sink.frames.get(0));
        assertEquals("item", item.path("type").asText());
        assertEquals("e1", item.path("streamId").asText());
        assertEquals("ready", item.path("value").path("type").asText());
        assertTrue(item.path("value").path("clientId").asText().length() > 0);
        assertEquals("/home/u", item.path("value").path("host").path("home").asText());
    }

    @Test
    void emitBroadcastsOnlyToOpenEventStreams() {
        RecordingSink sink = new RecordingSink();
        MuxSession session = new MuxSession("/home/u", sink);
        session.receive("{\"type\":\"open\",\"streamId\":\"e1\",\"endpoint\":\"$events\",\"payload\":{\"args\":{}}}");
        sink.frames.clear();

        session.emit("session/updated", Json.object().put("id", "s-9"));
        assertEquals(1, sink.frames.size());
        JsonNode item = Json.parseObject(sink.frames.get(0));
        assertEquals("e1", item.path("streamId").asText());
        assertEquals("emit", item.path("value").path("type").asText());
        assertEquals("session/updated", item.path("value").path("event").asText());
        assertEquals("s-9", item.path("value").path("args").path("id").asText());
    }

    @Test
    void cancelStopsBroadcast() {
        RecordingSink sink = new RecordingSink();
        MuxSession session = new MuxSession("/home/u", sink);
        session.receive("{\"type\":\"open\",\"streamId\":\"e1\",\"endpoint\":\"$events\",\"payload\":{\"args\":{}}}");
        session.receive("{\"type\":\"cancel\",\"streamId\":\"e1\"}");
        sink.frames.clear();
        session.emit("session/updated", Json.object().put("id", "s-9"));
        assertEquals(0, sink.frames.size(), "cancel 后不应再收到广播");
    }

    @Test
    void pingStreamItemsThenEnds() {
        RecordingSink sink = new RecordingSink();
        MuxSession session = new MuxSession("/home/u", sink);
        session.receive("{\"type\":\"open\",\"streamId\":\"p1\",\"endpoint\":\"ping\",\"payload\":{}}");
        assertEquals(2, sink.frames.size());
        assertEquals("item", Json.parseObject(sink.frames.get(0)).path("type").asText());
        assertEquals("end", Json.parseObject(sink.frames.get(1)).path("type").asText());
    }

    @Test
    void unknownEndpointReceivesErrorFrame() {
        RecordingSink sink = new RecordingSink();
        MuxSession session = new MuxSession("/home/u", sink);
        session.receive("{\"type\":\"open\",\"streamId\":\"x1\",\"endpoint\":\"sessions.stream\",\"payload\":{}}");
        assertEquals(1, sink.frames.size());
        JsonNode error = Json.parseObject(sink.frames.get(0));
        assertEquals("error", error.path("type").asText());
        assertEquals("gateway/lookup-not-found", error.path("error").path("code").asText());
    }

    @Test
    void duplicateOpenOfSameStreamIdIsRejected() {
        RecordingSink sink = new RecordingSink();
        MuxSession session = new MuxSession("/home/u", sink);
        session.receive("{\"type\":\"open\",\"streamId\":\"d1\",\"endpoint\":\"ping\",\"payload\":{}}");
        assertThrows(IllegalStateException.class, () ->
                session.receive("{\"type\":\"open\",\"streamId\":\"d1\",\"endpoint\":\"ping\",\"payload\":{}}"));
    }
}
