package com.bizfty.anchon.dsh.hostbridge.rpc;

import com.bizfty.anchon.dsh.hostbridge.json.Json;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * unary RPC 信封编解码测试：exact-key 纪律、rpcId/method 校验、响应渲染形状。
 */
class RpcCodecTest {

    private static String request(String rpcId, String method, String payload) {
        return "{\"type\":\"client-request\",\"rpcId\":\"" + rpcId
                + "\",\"method\":\"" + method + "\",\"payload\":" + payload + "}";
    }

    @Test
    void parsesWellFormedRequest() {
        ClientRequest req = RpcCodec.parseRequest(request("abc-1", "settings.view", "{\"ns\":\"agent\"}"));
        assertEquals("abc-1", req.rpcId());
        assertEquals("settings.view", req.method());
        assertEquals("agent", req.payload().path("ns").asText());
    }

    @Test
    void rejectsExtraKeys() {
        String body = request("abc-1", "ping", "{}").replace("}", ",\"x\":1}");
        assertThrows(RpcCodec.RpcEnvelopeException.class, () -> RpcCodec.parseRequest(body));
    }

    @Test
    void rejectsMissingKeysAndWrongType() {
        assertThrows(RpcCodec.RpcEnvelopeException.class, () ->
                RpcCodec.parseRequest("{\"type\":\"client-request\",\"rpcId\":\"a\",\"method\":\"ping\"}"));
        assertThrows(RpcCodec.RpcEnvelopeException.class, () ->
                RpcCodec.parseRequest(request("a", "ping", "{}").replace("client-request", "other")));
    }

    @Test
    void rejectsInvalidMethodSegment() {
        // '/' 不在 endpoint 段字符集 [A-Za-z0-9_$.-]+
        assertThrows(RpcCodec.RpcEnvelopeException.class, () ->
                RpcCodec.parseRequest(request("a", "settings/view", "{}")));
    }

    @Test
    void rendersOkAndErrorResults() {
        JsonNode ok = RpcCodec.okResult(Json.object().put("pong", true));
        assertEquals(true, ok.path("ok").asBoolean());
        assertEquals(true, ok.path("value").path("pong").asBoolean());

        JsonNode err = RpcCodec.errorResult("settings/conflict", "stale revision", null);
        assertEquals(false, err.path("ok").asBoolean());
        assertEquals("settings/conflict", err.path("error").path("code").asText());
        assertTrue(err.path("error").path("details").isObject());
    }

    @Test
    void rendersServerResponseEnvelope() {
        String text = RpcCodec.renderResponse("abc-1", RpcCodec.okResult(Json.object().put("pong", true)));
        JsonNode node = Json.parseObject(text);
        assertEquals("server-response", node.path("type").asText());
        assertEquals("abc-1", node.path("rpcId").asText());
        assertEquals(true, node.path("result").path("ok").asBoolean());
    }

    @Test
    void nullPayloadRoundTripsAsObject() {
        ClientRequest req = RpcCodec.parseRequest(
                "{\"type\":\"client-request\",\"rpcId\":\"a\",\"method\":\"ping\",\"payload\":null}");
        assertTrue(req.payload() == null || req.payload().isNull(), "JSON null 载荷应为 NullNode/可空");
    }
}
