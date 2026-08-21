package com.example.dsh.mcp;

import com.example.dsh.tool.AgentTool;
import com.example.dsh.tool.ToolCall;
import com.example.dsh.tool.ToolContext;
import com.example.dsh.tool.ToolRegistry;
import com.example.dsh.tool.ToolResult;
import com.example.dsh.tool.ToolSchema;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MCP 桥接测试：工具注册命名、schema 转换、调用转发、错误渲染。
 */
class McpToolManagerTest {

    private McpSchema.Tool sampleTool() {
        return new McpSchema.Tool("greet", "问候工具", "greet",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "name", Map.of("type", "string", "description", "名字")),
                        "required", List.of("name")),
                null, null, Map.of());
    }

    @SuppressWarnings("unchecked")
    private org.springframework.beans.factory.ObjectProvider<com.example.dsh.credentials.CredentialProvider> mockProvider(
            com.example.dsh.credentials.CredentialProvider... providers) {
        org.springframework.beans.factory.ObjectProvider<com.example.dsh.credentials.CredentialProvider> op =
                mock(org.springframework.beans.factory.ObjectProvider.class);
        when(op.orderedStream()).thenReturn(java.util.stream.Stream.of(providers));
        return op;
    }

    private ToolRegistry registryWith(AgentTool... tools) {
        ApplicationContext ctx = mock(ApplicationContext.class);
        java.util.Map<String, AgentTool> beans = new java.util.LinkedHashMap<>();
        for (AgentTool t : tools) {
            beans.put(t.name(), t);
        }
        when(ctx.getBeansOfType(AgentTool.class)).thenReturn(beans);
        return new ToolRegistry(ctx);
    }

    @Test
    void registersServerToolsWithPrefixedNames() {
        McpSyncClient client = mock(McpSyncClient.class);
        when(client.listTools()).thenReturn(new McpSchema.ListToolsResult(List.of(sampleTool()), null));
        ToolRegistry registry = registryWith();
        McpToolManager manager = new McpToolManager(registry, null, null);

        manager.registerServerTools("fs-server", client);

        assertEquals(1, registry.allTools().size());
        assertTrue(registry.hasTool("mcp__fs-server__greet"));
        AgentTool tool = registry.getTool("mcp__fs-server__greet");
        assertEquals("mcp__fs-server__greet", tool.name());
    }

    @Test
    void schemaIsConvertedFromInputSchema() {
        McpSyncClient client = mock(McpSyncClient.class);
        ToolRegistry registry = registryWith(new McpTool("mcp__s__greet", sampleTool(), client, "s"));
        AgentTool tool = registry.getTool("mcp__s__greet");

        ToolSchema schema = tool.getSchema();
        assertEquals("mcp__s__greet", schema.name());
        assertTrue(schema.parameters().containsKey("name"));
        assertEquals("string", schema.parameters().get("name").type());
        assertTrue(schema.required().contains("name"));
        Map<String, Object> inputSchema = schema.inputSchema();
        assertTrue(inputSchema.containsKey("properties"));
    }

    @Test
    void executeForwardsCallAndRendersText() {
        McpSyncClient client = mock(McpSyncClient.class);
        McpSchema.CallToolResult result = new McpSchema.CallToolResult(
                List.of(new McpSchema.TextContent("你好，世界")), false, null, Map.of());
        when(client.callTool(any())).thenReturn(result);
        McpTool tool = new McpTool("mcp__s__greet", sampleTool(), client, "s");

        ToolResult outcome = tool.execute(new ToolCall("c1", tool.name(), Map.of("name", "dsh")),
                ToolContext.builder().build());

        assertTrue(outcome.success());
        assertEquals("你好，世界", outcome.message());
        verify(client).callTool(any(McpSchema.CallToolRequest.class));
    }

    @Test
    void resolvesCredentialFromService() {
        com.example.dsh.credentials.CredentialService credentials = new com.example.dsh.credentials.CredentialService(
                mockProvider(new com.example.dsh.credentials.EnvCredentialProvider()));
        credentials.set(new com.example.dsh.credentials.CredentialRef("env", "DSH_MCP_TEST_TOKEN"), "tok-123");
        McpToolManager manager = new McpToolManager(registryWith(), null, credentials);

        McpProperties.ServerSpec spec = new McpProperties.ServerSpec();
        spec.setType("sse");
        spec.setUrl("http://127.0.0.1:1/sse");
        spec.setCredentialRef("env:DSH_MCP_TEST_TOKEN");
        assertEquals("tok-123", manager.resolveCredential(spec).orElse(""));
        // 未配置引用 → empty
        spec.setCredentialRef(null);
        assertTrue(manager.resolveCredential(spec).isEmpty());
    }

    @Test
    void errorResultIsFailure() {
        McpSyncClient client = mock(McpSyncClient.class);
        McpSchema.CallToolResult result = new McpSchema.CallToolResult(
                List.of(new McpSchema.TextContent("权限不足")), true, null, Map.of());
        when(client.callTool(any())).thenReturn(result);
        McpTool tool = new McpTool("mcp__s__greet", sampleTool(), client, "s");

        ToolResult outcome = tool.execute(new ToolCall("c1", tool.name(), Map.of()),
                ToolContext.builder().build());

        assertFalse(outcome.success());
        assertTrue(outcome.message().contains("权限不足"));
    }
}
