package com.bizfty.anchon.dsh.tool;

import com.bizfty.anchon.dsh.core.event.SessionEvent;
import com.bizfty.anchon.dsh.core.event.SessionEventBus;
import com.bizfty.anchon.dsh.core.event.SessionEventType;
import com.bizfty.anchon.dsh.core.model.SessionId;
import com.bizfty.anchon.dsh.util.JsonUtils;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 工具超时测试：声明 timeoutMs 的工具超时后返回结构化失败并发布 TOOL_TIMEOUT 事件。
 */
class ToolExecutionPipelineTimeoutTest {

    @Test
    void slowToolTimesOutWithStructuredResult() throws Exception {
        SessionId sessionId = SessionId.of("sess_1");
        ApplicationContext ctx = mock(ApplicationContext.class);
        when(ctx.getBeansOfType(AgentTool.class)).thenReturn(Map.of("slow", new SlowTool()));
        ToolRegistry registry = new ToolRegistry(ctx);

        SessionEventBus bus = new SessionEventBus();
        List<SessionEvent> events = new ArrayList<>();
        bus.addListener(events::add);
        ToolExecutionPipeline pipeline = new ToolExecutionPipeline(registry, List.of(), List.of(),
                new ToolEventPublisher(bus, new JsonUtils()), new JsonUtils());

        long start = System.currentTimeMillis();
        ToolResult result = pipeline.execute("slow", "{}",
                ToolContext.builder().sessionId(sessionId).build());
        long elapsed = System.currentTimeMillis() - start;

        assertFalse(result.success());
        assertTrue(result.message().contains("超时"));
        assertTrue(elapsed < 2000, "超时应快速返回，实际 " + elapsed + "ms");
        // TOOL_TIMEOUT 事件已发布（TOOL_CALL → TOOL_TIMEOUT）
        assertEquals(SessionEventType.TOOL_CALL, events.get(0).type());
        assertTrue(events.stream().anyMatch(e -> e.type() == SessionEventType.TOOL_TIMEOUT));
    }

    @Test
    void fastToolWithinTimeoutSucceeds() {
        SessionId sessionId = SessionId.of("sess_1");
        ApplicationContext ctx = mock(ApplicationContext.class);
        when(ctx.getBeansOfType(AgentTool.class)).thenReturn(Map.of("fast", new FastTool()));
        ToolRegistry registry = new ToolRegistry(ctx);
        ToolExecutionPipeline pipeline = new ToolExecutionPipeline(registry, List.of(), List.of(),
                new ToolEventPublisher(new SessionEventBus(), new JsonUtils()), new JsonUtils());

        ToolResult result = pipeline.execute("fast", "{}",
                ToolContext.builder().sessionId(sessionId).build());
        assertTrue(result.success());
    }

    @Tool(name = "slow", description = "慢工具", timeoutMs = 100)
    static class SlowTool implements AgentTool {
        @Override
        public String name() {
            return "slow";
        }

        @Override
        public ToolSchema getSchema() {
            return ToolSchema.builder().name(name()).description("").build();
        }

        @Override
        public ToolResult execute(ToolCall call, ToolContext context) {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return ToolResult.success("太慢了");
        }
    }

    @Tool(name = "fast", description = "快工具", timeoutMs = 2000)
    static class FastTool implements AgentTool {
        @Override
        public String name() {
            return "fast";
        }

        @Override
        public ToolSchema getSchema() {
            return ToolSchema.builder().name(name()).description("").build();
        }

        @Override
        public ToolResult execute(ToolCall call, ToolContext context) {
            return ToolResult.success("快");
        }
    }
}
