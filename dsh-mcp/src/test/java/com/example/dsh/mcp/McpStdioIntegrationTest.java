package com.example.dsh.mcp;

import com.example.dsh.tool.AgentTool;
import com.example.dsh.tool.ToolCall;
import com.example.dsh.tool.ToolContext;
import com.example.dsh.tool.ToolRegistry;
import com.example.dsh.tool.ToolResult;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 真实 stdio 传输集成测试：启动假 MCP 服务器进程（Python），
 * 验证 连接 → initialize → list_tools → 工具注册 → call_tool 全链路。
 */
class McpStdioIntegrationTest {

    private ToolRegistry emptyRegistry() {
        ApplicationContext ctx = mock(ApplicationContext.class);
        when(ctx.getBeansOfType(AgentTool.class)).thenReturn(Map.of());
        return new ToolRegistry(ctx);
    }

    @Test
    void realStdioServerEndToEnd() throws Exception {
        String scriptPath = "src/test/resources/fake_mcp_server.py";
        java.nio.file.Path script = java.nio.file.Paths.get(scriptPath);
        assertTrue(java.nio.file.Files.exists(script), "假服务器脚本应存在: " + script.toAbsolutePath());

        ToolRegistry registry = emptyRegistry();
        McpToolManager manager = new McpToolManager(registry, null, null);
        McpProperties.ServerSpec spec = new McpProperties.ServerSpec();
        spec.setType("stdio");
        spec.setCommand("python3");
        spec.setArgs(List.of(script.toAbsolutePath().toString()));

        try {
            manager.connectAndRegister("fake", spec);

            assertTrue(registry.hasTool("mcp__fake__py_echo"), "工具应按 mcp__server__tool 命名注册");
            AgentTool tool = registry.getTool("mcp__fake__py_echo");

            ToolResult result = tool.execute(new ToolCall("c1", tool.name(), Map.of("text", "hi")),
                    ToolContext.builder().build());
            assertTrue(result.success(), "调用应成功: " + result.message());
            assertTrue(result.message().contains("py-echo:hi"), "应包含假服务器返回: " + result.message());
        } finally {
            manager.closeAll();
        }
    }
}
