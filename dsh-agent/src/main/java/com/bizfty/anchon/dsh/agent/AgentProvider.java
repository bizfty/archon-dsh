package com.bizfty.anchon.dsh.agent;

import com.bizfty.anchon.dsh.core.model.Agent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Agent 解析 — 按 agentId 从配置查找（对应 DSH core/agent-default-model + preset）。
 * <p>
 * 未知 id fail loud（AgentNotFoundException）；默认 agent 取 {@code main}。
 */
@Component
public class AgentProvider {

    /** 默认 agent id（对应 DSH 部署 persona 槽）。 */
    public static final String DEFAULT_AGENT_ID = "main";

    private final AgentProperties properties;
    private final String defaultId;

    @Autowired
    public AgentProvider(Environment environment) {
        this(AgentProperties.from(environment), DEFAULT_AGENT_ID);
    }

    public AgentProvider(AgentProperties properties, String defaultId) {
        this.properties = properties;
        this.defaultId = defaultId;
    }

    /** 单 agent 便捷构造（测试/内嵌用）：按给定 id 注册一个 agent。 */
    public AgentProvider(String id, String name, String provider, String model,
                         String systemPrompt, String cwd) {
        AgentProperties props = new AgentProperties();
        Map<String, AgentProperties.AgentSpec> map = new LinkedHashMap<>();
        AgentProperties.AgentSpec spec = new AgentProperties.AgentSpec();
        spec.setProvider(provider == null || provider.isBlank() ? "deepseek" : provider);
        spec.setModel(model);
        spec.setSystemPrompt(systemPrompt);
        spec.setCwd(cwd);
        map.put(id, spec);
        props.setAgents(map);
        this.properties = props;
        this.defaultId = id;
    }

    public Agent defaultAgent() {
        return resolve(defaultId);
    }

    public Agent resolve(String agentId) {
        String id = (agentId == null || agentId.isBlank()) ? defaultId : agentId;
        AgentProperties.AgentSpec spec = properties.getAgents().get(id);
        if (spec == null) {
            throw new AgentNotFoundException("Agent 不存在: " + id
                    + "（可用: " + properties.getAgents().keySet() + "）");
        }
        return new Agent(id, id, spec.getProvider(), spec.getModel(),
                spec.getSystemPrompt(), spec.getCwd(),
                spec.getEnabledTools(), spec.getDisabledTools(), spec.getCredentialRef());
    }

    /** Agent 不存在（fail loud）。 */
    public static final class AgentNotFoundException extends RuntimeException {
        public AgentNotFoundException(String message) {
            super(message);
        }
    }
}
