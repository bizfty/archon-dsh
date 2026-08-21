package com.example.dsh.mcp;

import com.example.dsh.tool.AgentTool;
import com.example.dsh.tool.ToolCall;
import com.example.dsh.tool.ToolContext;
import com.example.dsh.tool.ToolResult;
import com.example.dsh.tool.ToolSchema;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.List;
import java.util.Map;
/**
 * MCP 工具适配 — 把外部 MCP 服务器的工具包装为本项目 AgentTool。
 * <p>
 * 公名 = mcp__&lt;serverName&gt;__&lt;rawName&gt;（与连接顺序无关的纯函数，对应 DSH 命名规范）。
 */
public class McpTool implements AgentTool {

    private final String publicName;
    private final McpSchema.Tool tool;
    private final McpSyncClient client;
    private final String serverName;

    public McpTool(String publicName, McpSchema.Tool tool, McpSyncClient client, String serverName) {
        this.publicName = publicName;
        this.tool = tool;
        this.client = client;
        this.serverName = serverName;
    }

    @Override
    public String name() {
        return publicName;
    }

    public String rawName() {
        return tool.name();
    }

    public String serverName() {
        return serverName;
    }

    @Override
    public ToolSchema getSchema() {
        ToolSchema.Builder builder = ToolSchema.builder()
                .name(publicName)
                .description(tool.description() == null ? tool.name() : tool.description());
        Map<String, Object> inputSchema = tool.inputSchema() == null ? Map.of() : tool.inputSchema();
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) inputSchema.getOrDefault("properties", Map.of());
        for (Map.Entry<String, Object> entry : properties.entrySet()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> prop = entry.getValue() instanceof Map<?, ?> m
                    ? (Map<String, Object>) m : Map.of();
            String type = String.valueOf(prop.getOrDefault("type", "string"));
            Object desc = prop.get("description");
            builder.addParameter(entry.getKey(), type, desc == null ? "" : String.valueOf(desc));
        }
        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) inputSchema.getOrDefault("required", List.of());
        builder.required(required.toArray(String[]::new));
        return builder.build();
    }

    @Override
    public ToolResult execute(ToolCall call, ToolContext context) {
        try {
            McpSchema.CallToolResult result = client.callTool(
                    new McpSchema.CallToolRequest(tool.name(), call.arguments()));
            String text = renderContent(result.content());
            if (Boolean.TRUE.equals(result.isError())) {
                return ToolResult.failure(text == null || text.isBlank() ? "MCP 工具调用失败" : text);
            }
            return ToolResult.success(text == null || text.isBlank() ? "(无输出)" : text);
        } catch (Exception e) {
            return ToolResult.failure("MCP 工具调用异常: " + e.getMessage());
        }
    }

    private String renderContent(List<McpSchema.Content> content) {
        if (content == null || content.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (McpSchema.Content c : content) {
            if (c instanceof McpSchema.TextContent text) {
                sb.append(text.text()).append('\n');
            } else {
                sb.append(String.valueOf(c)).append('\n');
            }
        }
        return sb.toString().trim();
    }
}
