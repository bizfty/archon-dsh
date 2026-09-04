package com.bizfty.anchon.dsh.api.hostbridge;

import com.bizfty.anchon.dsh.credentials.CredentialRef;
import com.bizfty.anchon.dsh.credentials.CredentialService;
import com.bizfty.anchon.dsh.hostbridge.json.Json;
import com.bizfty.anchon.dsh.hostbridge.mux.MuxSession;
import com.bizfty.anchon.dsh.hostbridge.mux.MuxSessionRegistry;
import com.bizfty.anchon.dsh.hostbridge.rpc.RpcBridgeService;
import com.bizfty.anchon.dsh.hostbridge.rpc.RpcDispatcher;
import com.bizfty.anchon.dsh.session.SessionService;
import com.bizfty.anchon.dsh.settings.SettingDescriptor;
import com.bizfty.anchon.dsh.settings.SettingsConflictException;
import com.bizfty.anchon.dsh.settings.SettingsService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * hostbridge 业务端点映射单测：settings/credentials/session 的
 * {@code {args}} payload → server-response value / ok:false 错误码（契约 §2 表）。
 */
class HostBridgeEndpointsTest {

    private final SettingsService settings = mock(SettingsService.class);
    private final CredentialService credentials = mock(CredentialService.class);
    private final SessionService sessions = mock(SessionService.class);
    private final MuxSessionRegistry registry = new MuxSessionRegistry();
    private final RpcBridgeService bridge = bridge();

    private RpcBridgeService bridge() {
        RpcDispatcher dispatcher = new RpcDispatcher();
        RpcBridgeService.registerBuiltins(dispatcher);
        SettingsEndpoints.register(dispatcher, settings, registry);
        CredentialsEndpoints.register(dispatcher, credentials);
        SessionEndpoints.register(dispatcher, sessions);
        return new RpcBridgeService(dispatcher);
    }

    private JsonNode call(String endpoint, ObjectNode args) {
        ObjectNode body = Json.object();
        body.put("type", "client-request");
        body.put("rpcId", "rpc-1");
        body.put("method", endpoint);
        body.set("payload", Json.object().set("args", args));
        RpcBridgeService.BridgeResponse response = bridge.handle(endpoint, Json.write(body));
        assertEquals(200, response.status(), endpoint);
        return Json.parse(response.body());
    }

    // ---- settings ----

    @Test
    void describeListsNamespacesWithRedactedView() {
        when(settings.describedNamespaces()).thenReturn(List.of("ui"));
        when(settings.describe("ui")).thenReturn(List.of(new SettingDescriptor(
                "theme", "enum", "主题", null, "light", List.of("light", "dark"), null,
                null, null, null, null, null, null)));
        when(settings.all("ui")).thenReturn(Map.of("theme", "light", "extra", 1));
        when(settings.userSection("ui")).thenReturn(Map.of());
        when(settings.applies("ui")).thenReturn("live");
        when(settings.revision("ui")).thenReturn(3L);

        JsonNode value = call("settings/describe", Json.object()).path("result").path("value");
        assertTrue(value.path("writable").asBoolean());
        JsonNode ns = value.path("namespaces").get(0);
        assertEquals("ui", ns.path("ns").asText());
        assertEquals("live", ns.path("applies").asText());
        assertEquals(3, ns.path("revision").asLong());
        assertEquals("light", ns.path("value").path("theme").asText());
        assertEquals("object", ns.path("schema").path("type").asText());
    }

    @Test
    void updateConflictsMapToSettingsConflict() {
        when(settings.describe("ui")).thenReturn(List.of());
        when(settings.all("ui")).thenReturn(Map.of());
        when(settings.userSection("ui")).thenReturn(Map.of());
        when(settings.applies("ui")).thenReturn("live");
        when(settings.revision("ui")).thenReturn(0L);
        doThrow(new SettingsConflictException("ui", 5, 2))
                .when(settings).update(eq("ui"), any(), eq(5L));

        JsonNode result = call("settings/update",
                Json.object().put("ns", "ui").put("expectedRevision", 5)
                        .set("patch", Json.object().put("theme", "dark")));
        assertFalse(result.path("result").path("ok").asBoolean());
        assertEquals("settings/conflict", result.path("result").path("error").path("code").asText());
        assertEquals(5, result.path("result").path("error").path("details").path("expected").asLong());
    }

    @Test
    void mutateEmitsDocumentUpdatedToEventStreams() {
        when(settings.describe("ui")).thenReturn(List.of());
        when(settings.all("ui")).thenReturn(Map.of());
        when(settings.userSection("ui")).thenReturn(Map.of());
        when(settings.applies("ui")).thenReturn("live");
        when(settings.revision("ui")).thenReturn(4L);
        when(settings.mutate(eq("ui"), any(), any())).thenReturn(4L);

        // 接一个 $events 会话并打开事件流，验证广播帧
        StringBuilder wire = new StringBuilder();
        MuxSession mux = new MuxSession("/home/u", wire::append);
        registry.register("ws-1", mux);
        mux.receive(Json.write(Json.object()
                .put("type", "open").put("streamId", "s1").put("endpoint", "$events")
                .set("payload", Json.object().set("args", Json.object()))));

        JsonNode result = call("settings/mutate", Json.object().put("ns", "ui")
                .put("expectedRevision", 3L)
                .set("ops", Json.array(Json.object().put("op", "set")
                        .set("path", Json.array("theme")).put("value", "dark"))));
        assertTrue(result.path("result").path("ok").asBoolean());

        String frame = wire.toString();
        assertTrue(frame.contains("\"type\":\"item\""), frame);
        assertTrue(frame.contains("settings/document-updated"), frame);
        assertTrue(frame.contains("\"ui\""), frame);
        assertTrue(frame.contains("4"), frame);
    }

    // ---- credentials ----

    @Test
    void credentialsDescribeSetUnset() {
        when(credentials.resolve(new CredentialRef("env", "MY_KEY")))
                .thenReturn(Optional.of("secret-value"));
        JsonNode describe = call("credentials/describe",
                Json.object().set("refs", Json.array("MY_KEY", "MISSING_KEY")));
        assertTrue(describe.path("result").path("value").path("MY_KEY").path("configured").asBoolean());
        assertFalse(describe.path("result").path("value").path("MISSING_KEY").path("configured").asBoolean());
        assertTrue(describe.path("result").path("value").path("MY_KEY").path("writable").asBoolean());
        assertFalse(describe.path("result").path("value").path("MY_KEY").has("source"));

        JsonNode set = call("credentials/set",
                Json.object().put("ref", "MY_KEY").put("value", "v"));
        assertTrue(set.path("result").path("ok").asBoolean());

        JsonNode unset = call("credentials/unset", Json.object().put("ref", "MY_KEY"));
        assertTrue(unset.path("result").path("ok").asBoolean());
    }

    @Test
    void credentialsBadRefRejected() {
        JsonNode describe = call("credentials/describe",
                Json.object().set("refs", Json.array("1bad")));
        assertFalse(describe.path("result").path("ok").asBoolean());
        assertEquals("gateway/bad-request",
                describe.path("result").path("error").path("code").asText());
    }

    // ---- session ----

    @Test
    void sessionListAndCreateMapToSessionService() {
        var session = new com.bizfty.anchon.dsh.core.model.Session(
                com.bizfty.anchon.dsh.core.model.SessionId.of("sess_1"), "会话1", null,
                "/home/u/w", java.time.Instant.parse("2026-09-01T00:00:00Z"),
                java.time.Instant.parse("2026-09-02T00:00:00Z"));
        when(sessions.listSessions()).thenReturn(List.of(session));
        when(sessions.createSession(any(), any(), eq("/tmp/ws"))).thenReturn(session);

        JsonNode list = call("session/list", Json.object());
        JsonNode item = list.path("result").path("value").path("items").get(0);
        assertEquals("sess_1", item.path("sessionId").asText());
        assertEquals("/home/u/w", item.path("cwd").asText());
        assertTrue(item.has("updatedAt"));

        JsonNode created = call("session/create",
                Json.object().put("cwd", "/tmp/ws"));
        assertEquals("sess_1", created.path("result").path("value").path("sessionId").asText());
    }
}
