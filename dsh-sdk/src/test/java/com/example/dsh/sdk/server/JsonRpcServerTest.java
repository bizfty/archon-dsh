package com.example.dsh.sdk.server;

import com.example.dsh.agent.AgentLoopService;
import com.example.dsh.agent.AgentRunResult;
import com.example.dsh.core.model.Session;
import com.example.dsh.core.model.SessionId;
import com.example.dsh.sdk.protocol.JsonRpc;
import com.example.dsh.session.SessionService;
import com.example.dsh.util.JsonUtils;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * stdio 服务器管道集成测试：真实行分隔读写，验证 初始化 → prompt → shutdown 闭环。
 */
class JsonRpcServerTest {

    @Test
    void stdioLineProtocolEndToEnd() throws Exception {
        SessionId sessionId = SessionId.of("sess_pipe");
        Session session = new Session(sessionId, "pipe", "deepseek-chat", null,
                Instant.now(), Instant.now());
        AgentLoopService loop = mock(AgentLoopService.class);
        SessionService sessions = mock(SessionService.class);
        when(sessions.getSession(sessionId)).thenReturn(session);
        when(loop.run(any())).thenAnswer(inv -> new AgentRunResult("管道回复", sessionId, 1, 0));
        JsonRpcServer server = new JsonRpcServer(new JsonRpcDispatcher(loop, sessions, new JsonUtils()));

        PipedInputStream serverIn = new PipedInputStream();
        PipedOutputStream testOut = new PipedOutputStream(serverIn);
        PipedInputStream testIn = new PipedInputStream();
        PipedOutputStream serverOut = new PipedOutputStream(testIn);

        Thread serverThread = Thread.startVirtualThread(() -> server.run(serverIn, serverOut));

        java.io.PrintWriter writer = new java.io.PrintWriter(testOut, true, StandardCharsets.UTF_8);
        BufferedReader reader = new BufferedReader(new InputStreamReader(testIn, StandardCharsets.UTF_8));

        writer.println("{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"method\":\"initialize\"}");
        String initLine = reader.readLine();
        assertTrue(initLine != null && initLine.contains("protocolVersion"), "initialize 应有响应: " + initLine);

        writer.println("{\"jsonrpc\":\"2.0\",\"id\":\"2\",\"method\":\"session/prompt\","
                + "\"params\":{\"session_id\":\"sess_pipe\",\"message\":\"hi\"}}");
        String promptLine = reader.readLine();
        JsonUtils jsonUtils = new JsonUtils();
        @SuppressWarnings("unchecked")
        Map<String, Object> prompt = (Map<String, Object>) jsonUtils.toMap(promptLine);
        assertEquals("2", String.valueOf(prompt.get("id")));
        assertEquals("管道回复", ((Map<?, ?>) prompt.get("result")).get("content"));

        writer.println("{\"jsonrpc\":\"2.0\",\"id\":\"3\",\"method\":\"shutdown\"}");
        String shutdownLine = reader.readLine();
        assertTrue(shutdownLine != null && shutdownLine.contains("\"ok\":true"));

        serverThread.join(TimeUnit.SECONDS.toMillis(10));
        assertTrue(!serverThread.isAlive(), "shutdown 后服务器应退出");
        testOut.close();
        serverOut.close();
    }
}
