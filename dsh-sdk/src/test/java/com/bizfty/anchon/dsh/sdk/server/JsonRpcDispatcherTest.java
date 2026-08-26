package com.bizfty.anchon.dsh.sdk.server;

import com.bizfty.anchon.dsh.agent.AgentLoopService;
import com.bizfty.anchon.dsh.agent.AgentRunRequest;
import com.bizfty.anchon.dsh.agent.AgentRunResult;
import com.bizfty.anchon.dsh.core.model.Session;
import com.bizfty.anchon.dsh.core.model.SessionId;
import com.bizfty.anchon.dsh.sdk.protocol.JsonRpc;
import com.bizfty.anchon.dsh.session.SessionService;
import com.bizfty.anchon.dsh.util.JsonUtils;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * JSON-RPC 分发器测试：方法映射、错误码、会话操作、shutdown。
 */
class JsonRpcDispatcherTest {

    private final SessionId sessionId = SessionId.of("sess_rpc");
    private final Session session = new Session(sessionId, "rpc", "deepseek-chat", null,
            Instant.now(), Instant.now());

    private JsonRpcDispatcher dispatcher(AgentLoopService loop, SessionService sessions) {
        return new JsonRpcDispatcher(loop, sessions, new JsonUtils());
    }

    @Test
    void initializeReturnsProtocolInfo() {
        JsonRpcDispatcher dispatcher = dispatcher(mock(AgentLoopService.class), mock(SessionService.class));
        JsonRpc.Response response = parse(dispatcher.handleLine(
                "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"method\":\"initialize\"}"));
        assertEquals("1", response.id());
        assertTrue(response.result() instanceof Map<?, ?>);
    }

    @Test
    void sessionPromptRunsTurnAndReturnsResult() {
        AgentLoopService loop = mock(AgentLoopService.class);
        SessionService sessions = mock(SessionService.class);
        when(sessions.getSession(sessionId)).thenReturn(session);
        when(loop.run(any())).thenAnswer(inv -> new AgentRunResult("你好", sessionId, 1, 0));
        JsonRpcDispatcher dispatcher = dispatcher(loop, sessions);

        JsonRpc.Response response = parse(dispatcher.handleLine(
                "{\"jsonrpc\":\"2.0\",\"id\":\"2\",\"method\":\"session/prompt\","
                        + "\"params\":{\"session_id\":\"sess_rpc\",\"message\":\"hi\"}}"));

        assertEquals("2", response.id());
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) response.result();
        assertEquals("sess_rpc", result.get("session_id"));
        assertEquals("你好", result.get("content"));
        verify(loop).run(any(AgentRunRequest.class));
    }

    @Test
    void sessionPromptCreatesSessionWhenMissingId() {
        AgentLoopService loop = mock(AgentLoopService.class);
        SessionService sessions = mock(SessionService.class);
        when(sessions.createSession(any(), any(), any())).thenReturn(session);
        when(loop.run(any())).thenAnswer(inv -> new AgentRunResult("ok", sessionId, 1, 0));
        JsonRpcDispatcher dispatcher = dispatcher(loop, sessions);

        JsonRpc.Response response = parse(dispatcher.handleLine(
                "{\"jsonrpc\":\"2.0\",\"id\":\"3\",\"method\":\"session/prompt\","
                        + "\"params\":{\"message\":\"hi\"}}"));
        assertEquals("sess_rpc", ((Map<?, ?>) response.result()).get("session_id"));
        verify(sessions).createSession(any(), any(), any());
    }

    @Test
    void unknownMethodReturnsError() {
        JsonRpcDispatcher dispatcher = dispatcher(mock(AgentLoopService.class), mock(SessionService.class));
        JsonRpc.Response response = parse(dispatcher.handleLine(
                "{\"jsonrpc\":\"2.0\",\"id\":\"4\",\"method\":\"nope\"}"));
        assertEquals(JsonRpc.METHOD_NOT_FOUND, response.error().code());
    }

    @Test
    void malformedJsonReturnsParseError() {
        JsonRpcDispatcher dispatcher = dispatcher(mock(AgentLoopService.class), mock(SessionService.class));
        JsonRpc.Response response = parse(dispatcher.handleLine("{not json"));
        assertEquals(JsonRpc.PARSE_ERROR, response.error().code());
    }

    @Test
    void shutdownSetsFlag() {
        JsonRpcDispatcher dispatcher = dispatcher(mock(AgentLoopService.class), mock(SessionService.class));
        JsonRpc.Response response = parse(dispatcher.handleLine(
                "{\"jsonrpc\":\"2.0\",\"id\":\"5\",\"method\":\"shutdown\"}"));
        assertEquals(Map.of("ok", true), response.result());
        assertTrue(dispatcher.isShutdownRequested());
    }

    @Test
    void missingMessageIsInvalidParams() {
        JsonRpcDispatcher dispatcher = dispatcher(mock(AgentLoopService.class), mock(SessionService.class));
        JsonRpc.Response response = parse(dispatcher.handleLine(
                "{\"jsonrpc\":\"2.0\",\"id\":\"6\",\"method\":\"session/prompt\",\"params\":{}}"));
        assertEquals(JsonRpc.INVALID_PARAMS, response.error().code());
    }

    @SuppressWarnings("unchecked")
    private JsonRpc.Response parse(String line) {
        JsonUtils jsonUtils = new JsonUtils();
        Map<String, Object> map = jsonUtils.toMap(line);
        return new JsonRpc.Response(
                String.valueOf(map.get("jsonrpc")),
                map.get("id") == null ? null : String.valueOf(map.get("id")),
                map.get("result"),
                map.get("error") == null ? null : new JsonRpc.Error(
                        ((Number) ((Map<String, Object>) map.get("error")).get("code")).intValue(),
                        String.valueOf(((Map<String, Object>) map.get("error")).get("message")),
                        ((Map<String, Object>) map.get("error")).get("data")));
    }
}
