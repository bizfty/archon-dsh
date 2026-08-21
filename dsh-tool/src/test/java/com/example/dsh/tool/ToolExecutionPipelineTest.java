package com.example.dsh.tool;

import com.example.dsh.core.event.SessionEvent;
import com.example.dsh.core.event.SessionEventBus;
import com.example.dsh.core.event.SessionEventType;
import com.example.dsh.core.model.SessionId;
import com.example.dsh.util.JsonUtils;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 工具执行管线测试：门控拒绝、执行、后处理、事件发布、未知工具。
 */
class ToolExecutionPipelineTest {

    private final SessionId sessionId = SessionId.of("sess_1");

    private ToolRegistry registryWith(ToolRegistryTest.EchoTool echoTool) {
        ApplicationContext ctx = mock(ApplicationContext.class);
        when(ctx.getBeansOfType(AgentTool.class)).thenReturn(Map.of("echoTool", echoTool));
        return new ToolRegistry(ctx);
    }

    private ToolContext context() {
        return ToolContext.builder().sessionId(sessionId).executionId("exec_1").cwd("/tmp").build();
    }

    @Test
    void executesToolAndPublishesEvents() {
        SessionEventBus bus = new SessionEventBus();
        List<SessionEvent> events = new ArrayList<>();
        bus.addListener(events::add);
        ToolExecutionPipeline pipeline = new ToolExecutionPipeline(
                registryWith(new ToolRegistryTest.EchoTool()),
                List.of(), List.of(), new ToolEventPublisher(bus, new JsonUtils()), new JsonUtils());

        ToolResult result = pipeline.execute("echo", "{\"text\":\"hi\"}", context());

        assertTrue(result.success());
        assertEquals("echo: hi", result.message());
        assertEquals(2, events.size());
        assertEquals(SessionEventType.TOOL_CALL, events.get(0).type());
        assertEquals(SessionEventType.TOOL_RESULT, events.get(1).type());
    }

    @Test
    void denyGateBlocksExecution() {
        ToolRegistryTest.EchoTool echoTool = new ToolRegistryTest.EchoTool();
        ToolPreExecuteGate gate = (call, ctx) -> Optional.of("沙箱拒绝: read-only");
        SessionEventBus bus = new SessionEventBus();
        List<SessionEvent> events = new ArrayList<>();
        bus.addListener(events::add);
        ToolExecutionPipeline pipeline = new ToolExecutionPipeline(
                registryWith(echoTool), List.of(gate), List.of(),
                new ToolEventPublisher(bus, new JsonUtils()), new JsonUtils());

        ToolResult result = pipeline.execute("echo", "{\"text\":\"hi\"}", context());

        assertFalse(result.success());
        assertTrue(result.message().contains("沙箱拒绝"));
        assertEquals(SessionEventType.TOOL_DENIED, events.get(0).type());
    }

    @Test
    void unknownToolFailsStructured() {
        ToolExecutionPipeline pipeline = new ToolExecutionPipeline(
                registryWith(new ToolRegistryTest.EchoTool()),
                List.of(), List.of(), new ToolEventPublisher(new SessionEventBus(), new JsonUtils()),
                new JsonUtils());
        ToolResult result = pipeline.execute("nope", "{}", context());
        assertFalse(result.success());
        assertTrue(result.message().contains("未知工具"));
    }

    @Test
    void postProcessorCanRewriteResult() {
        ToolPostProcessor uppercaser = (call, ctx, result) ->
                ToolResult.success("[" + result.message().toUpperCase() + "]", result.data());
        ToolExecutionPipeline pipeline = new ToolExecutionPipeline(
                registryWith(new ToolRegistryTest.EchoTool()),
                List.of(), List.of(uppercaser),
                new ToolEventPublisher(new SessionEventBus(), new JsonUtils()), new JsonUtils());
        ToolResult result = pipeline.execute("echo", "{\"text\":\"hi\"}", context());
        assertTrue(result.success());
        assertEquals("[ECHO: HI]", result.message());
    }
}
