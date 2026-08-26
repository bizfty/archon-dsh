package com.bizfty.anchon.dsh.agent;

import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 多 Agent 配置（对应 DSH agent-presets 的配置面）。
 * <p>
 * 例：
 * <pre>
 * dsh:
 *   agents:
 *     main:     { model: deepseek-chat, provider: deepseek }
 *     planner:  { model: deepseek-chat, systemPrompt: "你只做规划…" }
 * </pre>
 * <p>
 * 注意：{@code dsh.*} 前缀会被 DSH harness 的系统属性（DSH_SHELL/DSH_HOME/DSH_WEB_URL…）
 * 污染，导致类级 {@code @ConfigurationProperties} 绑定失效；因此这里用
 * {@link Binder} 显式绑定为 {@code Map<String, AgentSpec>}（已实测可行）。
 */
public class AgentProperties {

    private Map<String, AgentSpec> agents = new LinkedHashMap<>();

    public Map<String, AgentSpec> getAgents() {
        return agents;
    }

    public void setAgents(Map<String, AgentSpec> agents) {
        this.agents = agents;
    }

    /** 从 Environment 显式绑定 dsh.agents。 */
    public static AgentProperties from(Environment environment) {
        AgentProperties props = new AgentProperties();
        Binder.get(environment)
                .bind("dsh.agents", Bindable.mapOf(String.class, AgentSpec.class))
                .ifBound(props::setAgents);
        return props;
    }

    /** 单个 Agent 配置。 */
    public static class AgentSpec {
        private String provider = "deepseek";
        private String model;
        private String systemPrompt;
        private String cwd;
        private String credentialRef;
        private java.util.List<String> enabledTools = java.util.List.of();
        private java.util.List<String> disabledTools = java.util.List.of();

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public String getSystemPrompt() {
            return systemPrompt;
        }

        public void setSystemPrompt(String systemPrompt) {
            this.systemPrompt = systemPrompt;
        }

        public String getCwd() {
            return cwd;
        }

        public void setCwd(String cwd) {
            this.cwd = cwd;
        }

        /** 该 agent 的 LLM API key 凭据引用（如 env:MY_KEY）；只落引用不落密钥。 */
        public String getCredentialRef() {
            return credentialRef;
        }

        public void setCredentialRef(String credentialRef) {
            this.credentialRef = credentialRef;
        }

        public java.util.List<String> getEnabledTools() {
            return enabledTools;
        }

        public void setEnabledTools(java.util.List<String> enabledTools) {
            this.enabledTools = enabledTools == null ? java.util.List.of() : enabledTools;
        }

        public java.util.List<String> getDisabledTools() {
            return disabledTools;
        }

        public void setDisabledTools(java.util.List<String> disabledTools) {
            this.disabledTools = disabledTools == null ? java.util.List.of() : disabledTools;
        }
    }
}
