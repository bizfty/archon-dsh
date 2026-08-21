package com.example.dsh.hooks;

import com.example.dsh.core.event.SessionEventBus;
import com.example.dsh.core.model.SessionId;
import com.example.dsh.tool.AgentTool;
import com.example.dsh.tool.ToolCall;
import com.example.dsh.tool.ToolContext;
import com.example.dsh.tool.ToolEventPublisher;
import com.example.dsh.tool.ToolExecutionPipeline;
import com.example.dsh.tool.ToolRegistry;
import com.example.dsh.tool.ToolResult;
import com.example.dsh.tool.ToolSchema;
import com.example.dsh.util.JsonUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Hook 门与管线集成测试：PreToolUse block 拒绝执行、continue 放行执行、PostToolUse 执行。
 */
class HookGateIntegrationTest {

    @TempDir
    Path dir;

    static final class EchoTool implements AgentTool {
        final AtomicInteger executed = new AtomicInteger();

        @Override
        public String name() {
            return "echo";
        }

        @Override
        public ToolSchema getSchema() {
            return ToolSchema.builder().name(name()).description("回显")
                    .addParameter("text", "string", "文本").required("text").build();
        }

        @Override
        public ToolResult execute(ToolCall call, ToolContext context) {
            executed.incrementAndGet();
            return ToolResult.success("echo: " + call.getString("text"));
        }
    }

    private ToolExecutionPipeline pipeline(Path hooksJson, EchoTool tool) throws Exception {
        HookConfig config = new HookConfig(hooksJson.toString());
        HookRunner runner = new HookRunner(new JsonUtils(), 5000);
        HookBridge.HookGate gate = new HookBridge.HookGate(config, runner);
        HookBridge.HookPostProcessor post = new HookBridge.HookPostProcessor(config, runner);
        ApplicationContext ctx = mock(ApplicationContext.class);
        when(ctx.getBeansOfType(AgentTool.class)).thenReturn(Map.of("echoTool", tool));
        ToolRegistry registry = new ToolRegistry(ctx);
        SessionEventBus bus = new SessionEventBus();
        return new ToolExecutionPipeline(registry, List.of(gate), List.of(post),
                new ToolEventPublisher(bus, new JsonUtils()), new JsonUtils());
    }

    private ToolContext context() {
        return ToolContext.builder().sessionId(SessionId.of("s_hook")).cwd(dir.toString()).build();
    }

    @Test
    void blockDecisionDeniesExecution() throws Exception {
        Path hooks = dir.resolve("hooks.json");
        Files.writeString(hooks, """
                {"hooks": {"PreToolUse": [{"matcher": "echo", "hooks": [
                  {"command": "printf '{\\"decision\\":\\"block\\",\\"reason\\":\\"测试拒绝\\"}'"}]}]}}
                """);
        EchoTool tool = new EchoTool();
        ToolExecutionPipeline pipeline = pipeline(hooks, tool);
        ToolResult result = pipeline.execute("echo", "{\"text\":\"hi\"}", context());
        assertFalse(result.success());
        assertTrue(result.message().contains("测试拒绝"));
        assertEquals(0, tool.executed.get(), "block 后工具不应执行");
    }

    @Test
    void continueDecisionAllowsExecution() throws Exception {
        Path hooks = dir.resolve("hooks.json");
        Files.writeString(hooks, """
                {"hooks": {"PreToolUse": [{"matcher": "echo", "hooks": [
                  {"command": "printf '{\\"decision\\":\\"continue\\"}'"}]}]}}
                """);
        EchoTool tool = new EchoTool();
        ToolExecutionPipeline pipeline = pipeline(hooks, tool);
        ToolResult result = pipeline.execute("echo", "{\"text\":\"hi\"}", context());
        assertTrue(result.success());
        assertEquals("echo: hi", result.message());
        assertEquals(1, tool.executed.get(), "continue 后工具应执行");
    }

    @Test
    void noMatchingHookPassesThrough() throws Exception {
        Path hooks = dir.resolve("hooks.json");
        Files.writeString(hooks, """
                {"hooks": {"PreToolUse": [{"matcher": "bash", "hooks": [
                  {"command": "printf '{\\"decision\\":\\"block\\"}'"}]}]}}
                """);
        EchoTool tool = new EchoTool();
        ToolExecutionPipeline pipeline = pipeline(hooks, tool);
        ToolResult result = pipeline.execute("echo", "{\"text\":\"hi\"}", context());
        assertTrue(result.success(), "matcher 不匹配应直通");
        assertEquals(1, tool.executed.get());
    }

    @Test
    void postToolUseHookRunsAfterExecution() throws Exception {
        Path hooks = dir.resolve("hooks.json");
        Path marker = dir.resolve("post-ran");
        Files.writeString(hooks, """
                {"hooks": {"PostToolUse": [{"matcher": "echo", "hooks": [
                  {"command": "touch %s"}]}]}}
                """.formatted(marker.toString()));
        EchoTool tool = new EchoTool();
        ToolExecutionPipeline pipeline = pipeline(hooks, tool);
        ToolResult result = pipeline.execute("echo", "{\"text\":\"hi\"}", context());
        assertTrue(result.success());
        assertTrue(Files.exists(marker), "PostToolUse hook 应已执行");
    }
}
