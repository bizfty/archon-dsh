package com.example.dsh.mcp;

import com.example.dsh.tool.ToolRegistry;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import tools.jackson.databind.json.JsonMapper;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.DependsOn;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * MCP 服务器管理器 — 启动时连接配置的服务器并把工具注册进 ToolRegistry
 * （对应 DSH mcp-client：每服务器一个插件实例，工具注册为原生工具）。
 * <p>
 * 连接失败仅告警跳过（服务器可能未就绪）；配置错误（未知 type）fail loud。
 */
@Component
@DependsOn("toolRegistry")
public class McpToolManager {

    private static final Logger log = LoggerFactory.getLogger(McpToolManager.class);

    private final ToolRegistry toolRegistry;
    private final Environment environment;
    private final com.example.dsh.credentials.CredentialService credentialService;
    private final List<McpSyncClient> clients = new ArrayList<>();

    public McpToolManager(ToolRegistry toolRegistry, Environment environment,
                          com.example.dsh.credentials.CredentialService credentialService) {
        this.toolRegistry = toolRegistry;
        this.environment = environment;
        this.credentialService = credentialService;
    }

    @PostConstruct
    public void connectAll() {
        McpProperties properties = McpProperties.from(environment);
        for (var entry : properties.getServers().entrySet()) {
            McpProperties.ServerSpec spec = entry.getValue();
            if (!spec.isEnabled()) {
                continue;
            }
            try {
                connectAndRegister(entry.getKey(), spec);
                log.info("[MCP] 服务器 '{}' 工具已注册", entry.getKey());
            } catch (IllegalArgumentException e) {
                throw e; // 配置错误 fail loud
            } catch (Exception e) {
                log.warn("[MCP] 连接服务器 '{}' 失败（跳过）: {}", entry.getKey(), e.getMessage());
            }
        }
    }

    /** 连接单个服务器并注册其工具（分离以便测试/热加载）。 */
    public void connectAndRegister(String serverName, McpProperties.ServerSpec spec) {
        registerServerTools(serverName, connect(spec));
    }

    private McpSyncClient connect(McpProperties.ServerSpec spec) {
        McpClientTransport transport = buildTransport(spec, resolveCredential(spec));
        McpSyncClient client = McpClient.sync(transport)
                .clientInfo(new McpSchema.Implementation("dsh-java", "0.1.0"))
                .build();
        client.initialize();
        clients.add(client);
        return client;
    }

    /** 解析服务器凭据（credentialRef → CredentialService；未配置返回 empty）。 */
    public java.util.Optional<String> resolveCredential(McpProperties.ServerSpec spec) {
        String ref = spec.getCredentialRef();
        if (ref == null || ref.isBlank() || credentialService == null) {
            return java.util.Optional.empty();
        }
        try {
            return credentialService.resolve(com.example.dsh.credentials.CredentialRef.parse(ref));
        } catch (IllegalArgumentException e) {
            return java.util.Optional.empty();
        }
    }

    private McpClientTransport buildTransport(McpProperties.ServerSpec spec,
                                              java.util.Optional<String> credential) {
        return switch (spec.getType()) {
            case "stdio" -> {
                if (spec.getCommand() == null || spec.getCommand().isBlank()) {
                    throw new IllegalArgumentException("MCP stdio 服务器缺少 command: " + spec);
                }
                ServerParameters parameters = ServerParameters.builder(spec.getCommand())
                        .args(spec.getArgs()).build();
                yield new StdioClientTransport(parameters, new JacksonMcpJsonMapper(JsonMapper.builder().build()));
            }
            case "sse" -> {
                if (spec.getUrl() == null || spec.getUrl().isBlank()) {
                    throw new IllegalArgumentException("MCP sse 服务器缺少 url: " + spec);
                }
                var builder = HttpClientSseClientTransport.builder(spec.getUrl())
                        .jsonMapper(new JacksonMcpJsonMapper(JsonMapper.builder().build()));
                // 凭据消费：Authorization: Bearer <token>（对应 DSH 每操作解析语义）
                if (credential.isPresent()) {
                    builder.requestBuilder(java.net.http.HttpRequest.newBuilder()
                            .header("Authorization", "Bearer " + credential.get()));
                }
                yield builder.build();
            }
            default -> throw new IllegalArgumentException("未知 MCP transport 类型: " + spec.getType()
                    + "（支持 stdio/sse）");
        };
    }

    /** 把服务器的工具注册进 ToolRegistry（分离以便测试）。 */
    public void registerServerTools(String serverName, McpSyncClient client) {
        McpSchema.ListToolsResult result = client.listTools();
        for (McpSchema.Tool tool : result.tools()) {
            String publicName = "mcp__" + serverName + "__" + normalizeName(tool.name());
            toolRegistry.registerDynamic(new McpTool(publicName, tool, client, serverName));
            log.info("[MCP]   [Tool] {}", publicName);
        }
    }

    /** 工具名规范化（64 字符上限；保留可读性，极端名确定性截断）。 */
    private String normalizeName(String raw) {
        String name = raw == null ? "unnamed" : raw.trim();
        if (name.length() > 64) {
            name = name.substring(0, 64) + "_" + Integer.toHexString(name.hashCode());
        }
        return name;
    }

    @PreDestroy
    public void closeAll() {
        for (McpSyncClient client : clients) {
            try {
                client.close();
            } catch (Exception e) {
                log.warn("[MCP] 关闭客户端失败: {}", e.getMessage());
            }
        }
        clients.clear();
    }
}
