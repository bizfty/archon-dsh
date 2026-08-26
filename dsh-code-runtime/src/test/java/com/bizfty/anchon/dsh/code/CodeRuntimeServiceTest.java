package com.bizfty.anchon.dsh.code;

import com.bizfty.anchon.dsh.core.event.SessionEventBus;
import com.bizfty.anchon.dsh.core.model.SessionId;
import com.bizfty.anchon.dsh.tool.AgentTool;
import com.bizfty.anchon.dsh.tool.Tool;
import com.bizfty.anchon.dsh.tool.ToolCall;
import com.bizfty.anchon.dsh.tool.ToolContext;
import com.bizfty.anchon.dsh.tool.ToolExecutionPipeline;
import com.bizfty.anchon.dsh.tool.ToolEventPublisher;
import com.bizfty.anchon.dsh.tool.ToolRegistry;
import com.bizfty.anchon.dsh.tool.ToolResult;
import com.bizfty.anchon.dsh.tool.ToolSchema;
import com.bizfty.anchon.dsh.util.JsonUtils;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Code Mode 集成测试：Node.js 与 Python 真实执行模型程序，经 JSON-RPC 回环调用管线工具。
 */
class CodeRuntimeServiceTest {

    private final SessionId sessionId = SessionId.of("sess_code");

    private ToolExecutionPipeline pipelineWith(AtomicInteger echoExecuted) {
        ApplicationContext ctx = mock(ApplicationContext.class);
        when(ctx.getBeansOfType(AgentTool.class))
                .thenReturn(Map.of("echoTool", new EchoTool(echoExecuted)));
        ToolRegistry registry = new ToolRegistry(ctx);
        SessionEventBus bus = new SessionEventBus();
        return new ToolExecutionPipeline(registry, List.of(), List.of(),
                new ToolEventPublisher(bus, new JsonUtils()), new JsonUtils());
    }

    // ===== JavaScript (Node.js) =====

    @Test
    void jsCallsToolAndReturnsResult() {
        AtomicInteger echoExecuted = new AtomicInteger();
        CodeRuntime runtime = new NodeCodeRuntime(pipelineWith(echoExecuted),
                "/usr/local/node/bin/node", 30_000, 262_144);
        String code = """
                const r = await tools.echo({text: "hi"});
                return "got: " + r.message;
                """;
        CodeRunResult result = runtime.run(code,
                ToolContext.builder().sessionId(sessionId).build(), 30_000);

        assertFalse(result.failed(), "不应失败: " + result.error());
        assertEquals("got: echo: hi", String.valueOf(result.result()));
        assertEquals(1, echoExecuted.get(), "echo 工具应被程序调用一次");
    }

    @Test
    void jsCapturesConsoleLogs() {
        CodeRuntime runtime = new NodeCodeRuntime(pipelineWith(new AtomicInteger()),
                "/usr/local/node/bin/node", 30_000, 262_144);
        CodeRunResult result = runtime.run(
                "console.log('step-1'); console.log('step-2'); return 42;",
                ToolContext.builder().sessionId(sessionId).build(), 30_000);
        assertFalse(result.failed());
        assertEquals(42, result.result());
        assertTrue(result.logs().contains("step-1"));
        assertTrue(result.logs().contains("step-2"));
    }

    @Test
    void jsProgramErrorIsReported() {
        CodeRuntime runtime = new NodeCodeRuntime(pipelineWith(new AtomicInteger()),
                "/usr/local/node/bin/node", 30_000, 262_144);
        CodeRunResult result = runtime.run("throw new Error('boom');",
                ToolContext.builder().sessionId(sessionId).build(), 30_000);
        assertTrue(result.failed());
        assertTrue(result.error().contains("boom"));
    }

    @Test
    void jsUnknownToolRejectsInProgram() {
        CodeRuntime runtime = new NodeCodeRuntime(pipelineWith(new AtomicInteger()),
                "/usr/local/node/bin/node", 30_000, 262_144);
        CodeRunResult result = runtime.run(
                "try { await tools.nope({}); return 'should-not'; } catch (e) { return 'caught:' + e.message; }",
                ToolContext.builder().sessionId(sessionId).build(), 30_000);
        assertFalse(result.failed());
        assertTrue(String.valueOf(result.result()).contains("caught"),
                "未知工具应 reject 并被程序捕获: " + result.result());
    }

    @Test
    void jsToolFailureRejectsInProgram() {
        ApplicationContext ctx = mock(ApplicationContext.class);
        when(ctx.getBeansOfType(AgentTool.class)).thenReturn(Map.of("boomTool", new BoomTool()));
        ToolRegistry registry = new ToolRegistry(ctx);
        SessionEventBus bus = new SessionEventBus();
        ToolExecutionPipeline pipeline = new ToolExecutionPipeline(registry, List.of(), List.of(),
                new ToolEventPublisher(bus, new JsonUtils()), new JsonUtils());
        CodeRuntime runtime = new NodeCodeRuntime(pipeline, "/usr/local/node/bin/node", 30_000, 262_144);
        CodeRunResult result = runtime.run(
                "try { await tools.boom_tool({}); return 'not-caught'; } catch (e) { return 'caught:' + e.message; }",
                ToolContext.builder().sessionId(sessionId).build(), 30_000);
        assertFalse(result.failed());
        assertTrue(String.valueOf(result.result()).contains("boom-失败"), "工具失败应 reject: " + result.result());
    }

    // ===== Python (python3) =====

    @Test
    void pythonCallsToolAndReturnsResult() {
        AtomicInteger echoExecuted = new AtomicInteger();
        CodeRuntime runtime = new PythonCodeRuntime(pipelineWith(echoExecuted),
                "python3", 30_000, 262_144);
        String code = "r = await tools.echo({'text': 'hi'})\nreturn 'got: ' + r['message']";
        CodeRunResult result = runtime.run(code,
                ToolContext.builder().sessionId(sessionId).build(), 30_000);

        assertFalse(result.failed(), "不应失败: " + result.error());
        assertEquals("got: echo: hi", String.valueOf(result.result()));
        assertEquals(1, echoExecuted.get());
    }

    @Test
    void pythonCapturesPrintLogs() {
        CodeRuntime runtime = new PythonCodeRuntime(pipelineWith(new AtomicInteger()),
                "python3", 30_000, 262_144);
        CodeRunResult result = runtime.run(
                "print('step-1')\nprint('step-2')\nreturn 42",
                ToolContext.builder().sessionId(sessionId).build(), 30_000);
        assertFalse(result.failed());
        assertEquals(42, result.result());
        assertTrue(result.logs().contains("step-1"));
        assertTrue(result.logs().contains("step-2"));
    }

    @Test
    void pythonProgramErrorIsReported() {
        CodeRuntime runtime = new PythonCodeRuntime(pipelineWith(new AtomicInteger()),
                "python3", 30_000, 262_144);
        CodeRunResult result = runtime.run("raise ValueError('boom')",
                ToolContext.builder().sessionId(sessionId).build(), 30_000);
        assertTrue(result.failed());
        assertTrue(result.error().contains("boom"));
    }

    @Test
    void pythonUnknownToolRejectsInProgram() {
        CodeRuntime runtime = new PythonCodeRuntime(pipelineWith(new AtomicInteger()),
                "python3", 30_000, 262_144);
        CodeRunResult result = runtime.run(
                "try:\n    await tools.nope({})\n    return 'should-not'\nexcept Exception as e:\n    return 'caught:' + str(e)",
                ToolContext.builder().sessionId(sessionId).build(), 30_000);
        assertFalse(result.failed());
        assertTrue(String.valueOf(result.result()).contains("caught"),
                "未知工具应 reject 并被程序捕获: " + result.result());
    }

    @Test
    void routerSelectsByLanguage() {
        AtomicInteger echoExecuted = new AtomicInteger();
        CodeRuntime nodeRuntime = new NodeCodeRuntime(pipelineWith(echoExecuted),
                "/usr/local/node/bin/node", 30_000, 262_144);
        CodeRuntime pythonRuntime = new PythonCodeRuntime(pipelineWith(echoExecuted),
                "python3", 30_000, 262_144);
        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<CodeRuntime> op =
                mock(org.springframework.beans.factory.ObjectProvider.class);
        when(op.orderedStream()).thenReturn(java.util.stream.Stream.of(nodeRuntime, pythonRuntime));
        CodeRuntimeService router = new CodeRuntimeService(op);
        assertEquals("js", router.runtimeFor(null).language());
        assertEquals("js", router.runtimeFor("js").language());
        assertEquals("python", router.runtimeFor("python").language());
        try {
            router.runtimeFor("ruby");
            assertFalse(true, "未知语言应抛异常");
        } catch (IllegalArgumentException expected) {
        }
    }

    @Tool(name = "echo", description = "回显")
    public static class EchoTool implements AgentTool {
        private final AtomicInteger executed;

        EchoTool(AtomicInteger executed) {
            this.executed = executed;
        }

        @Override
        public String name() {
            return "echo";
        }

        @Override
        public ToolSchema getSchema() {
            return ToolSchema.builder().name(name()).description("")
                    .addParameter("text", "string", "文本").required("text").build();
        }

        @Override
        public ToolResult execute(ToolCall call, ToolContext context) {
            executed.incrementAndGet();
            return ToolResult.success("echo: " + call.getString("text"));
        }
    }

    @Tool(name = "boom_tool", description = "总是失败")
    public static class BoomTool implements AgentTool {
        @Override
        public String name() {
            return "boom_tool";
        }

        @Override
        public ToolSchema getSchema() {
            return ToolSchema.builder().name(name()).description("").build();
        }

        @Override
        public ToolResult execute(ToolCall call, ToolContext context) {
            return ToolResult.failure("boom-失败");
        }
    }
}
