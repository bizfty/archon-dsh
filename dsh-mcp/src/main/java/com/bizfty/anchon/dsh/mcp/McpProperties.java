package com.bizfty.anchon.dsh.mcp;

import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 服务器配置（对应 DSH mcp-client 的每服务器插件实例）。
 * <p>
 * 例：
 * <pre>
 * dsh:
 *   mcp:
 *     servers:
 *       fs:
 *         type: stdio
 *         command: npx
 *         args: ["-y", "@modelcontextprotocol/server-filesystem", "/tmp"]
 *       remote:
 *         type: sse
 *         url: http://127.0.0.1:3001/sse
 * </pre>
 * 用 {@link Binder} 显式绑定（与 AgentProperties 相同原因：dsh.* 前缀可能被 harness
 * 系统属性污染，类级 @ConfigurationProperties 不可靠）。
 */
public class McpProperties {

    private Map<String, ServerSpec> servers = new LinkedHashMap<>();

    public Map<String, ServerSpec> getServers() {
        return servers;
    }

    public void setServers(Map<String, ServerSpec> servers) {
        this.servers = servers;
    }

    public static McpProperties from(Environment environment) {
        McpProperties props = new McpProperties();
        Binder.get(environment)
                .bind("dsh.mcp.servers", Bindable.mapOf(String.class, ServerSpec.class))
                .ifBound(props::setServers);
        return props;
    }

    /** 单个 MCP 服务器配置。 */
    public static class ServerSpec {
        /** stdio | sse */
        private String type = "stdio";
        private String command;
        private List<String> args = List.of();
        private String url;
        private boolean enabled = true;
        private String credentialRef;

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getCommand() {
            return command;
        }

        public void setCommand(String command) {
            this.command = command;
        }

        public List<String> getArgs() {
            return args;
        }

        public void setArgs(List<String> args) {
            this.args = args == null ? List.of() : args;
        }

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public boolean isEnabled() {
            return enabled;
        }

        /** 凭据引用（provider:key，经 CredentialService 解析；用于 Authorization 头）。 */
        public String getCredentialRef() {
            return credentialRef;
        }

        public void setCredentialRef(String credentialRef) {
            this.credentialRef = credentialRef;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}
