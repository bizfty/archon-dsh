package com.bizfty.anchon.dsh.hostbridge.rpc;

import com.bizfty.anchon.dsh.hostbridge.json.Json;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * unary RPC 服务门面测试：HTTP 语义（200/400/404）+ 信封形状 + 业务失败信封。
 */
class RpcBridgeServiceTest {

    private static String request(String rpcId, String method, String payload) {
        return "{\"type\":\"client-request\",\"rpcId\":\"" + rpcId
                + "\",\"method\":\"" + method + "\",\"payload\":" + payload + "}";
    }

    private RpcBridgeService service() {
        RpcDispatcher dispatcher = new RpcDispatcher();
        RpcBridgeService.registerBuiltins(dispatcher);
        return new RpcBridgeService(dispatcher);
    }

    @Test
    void pingReturnsOkResult() {
        RpcBridgeService.BridgeResponse response = service().handle("ping", request("id-1", "ping", "{}"));
        assertEquals(200, response.status());
        JsonNode envelope = Json.parseObject(response.body());
        assertEquals("server-response", envelope.path("type").asText());
        assertEquals("id-1", envelope.path("rpcId").asText());
        assertEquals(true, envelope.path("result").path("ok").asBoolean());
        assertEquals(true, envelope.path("result").path("value").path("pong").asBoolean());
    }

    @Test
    void echoReturnsPayloadVerbatim() {
        RpcBridgeService.BridgeResponse response = service().handle("echo", request("id-2", "echo", "{\"a\":1}"));
        assertEquals(200, response.status());
        JsonNode envelope = Json.parseObject(response.body());
        assertEquals(1, envelope.path("result").path("value").path("a").asInt());
    }

    @Test
    void unregisteredEndpointIs404() {
        RpcBridgeService.BridgeResponse response = service().handle("sessions.list", request("id-3", "sessions.list", "{}"));
        assertEquals(404, response.status());
    }

    @Test
    void malformedEnvelopeIs400() {
        RpcBridgeService.BridgeResponse response = service().handle("ping", "not-json");
        assertEquals(400, response.status());
        RpcBridgeService.BridgeResponse extra = service().handle("ping",
                request("a", "ping", "{}").replace("}", ",\"z\":2}"));
        assertEquals(400, extra.status());
    }

    @Test
    void businessFailureRidesEnvelopeWithHttp200() {
        RpcDispatcher dispatcher = new RpcDispatcher();
        RpcBridgeService.registerBuiltins(dispatcher);
        dispatcher.register("settings.write", payload -> {
            throw new RpcDispatcher.RpcHandlerException("settings/conflict", "stale revision", Json.object());
        });
        RpcBridgeService.BridgeResponse response = new RpcBridgeService(dispatcher)
                .handle("settings.write", request("id-4", "settings.write", "{}"));
        assertEquals(200, response.status());
        JsonNode envelope = Json.parseObject(response.body());
        assertEquals(false, envelope.path("result").path("ok").asBoolean());
        assertEquals("settings/conflict", envelope.path("result").path("error").path("code").asText());
    }

    @Test
    void duplicateEndpointRegistrationFails() {
        RpcDispatcher dispatcher = new RpcDispatcher();
        dispatcher.register("x", payload -> Json.object());
        assertThrows(IllegalStateException.class, () -> dispatcher.register("x", payload -> Json.object()));
    }
}
