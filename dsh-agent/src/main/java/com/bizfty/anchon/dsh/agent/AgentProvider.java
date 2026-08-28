package com.bizfty.anchon.dsh.agent;

import com.bizfty.anchon.dsh.core.model.Agent;
import com.bizfty.anchon.dsh.core.prompt.PromptTemplateRenderer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Agent 解析 — 按 agentId 从配置查找（对应 DSH core/agent-default-model + preset）。
 * <p>
 * persona 不走配置：按约定从 classpath 直接加载 {@code prompt/{agentId}.txt} 模板，
 * 存在则渲染为 system prompt，不存在则返回 null（由 {@code SystemPromptService}
 * 回退默认 persona）。未知 id fail loud（AgentNotFoundException）；默认 agent 取 {@code main}。
 */
@Component
public class AgentProvider {

    /** 默认 agent id（对应 DSH 部署 persona 槽）。 */
    public static final String DEFAULT_AGENT_ID = "main";

    /** persona 模板约定路径前缀（classpath），实际路径 = prompt/{agentId}.txt。 */
    static final String PROMPT_PATH_PREFIX = "prompt/";

    private final AgentProperties properties;
    private final String defaultId;
    private final PromptTemplateRenderer templateRenderer;
    private final ResourceLoader resourceLoader;
    /** 便捷构造（测试/内嵌）直接给定的 persona；非 null 时跳过约定模板加载。 */
    private final String directSystemPrompt;

    @Autowired
    public AgentProvider(Environment environment, PromptTemplateRenderer templateRenderer,
                         ResourceLoader resourceLoader) {
        this(AgentProperties.from(environment), DEFAULT_AGENT_ID, templateRenderer, resourceLoader);
    }

    /** 纯配置构造（测试用）：无模板能力，persona 一律留空（回退默认 persona）。 */
    public AgentProvider(AgentProperties properties, String defaultId) {
        this(properties, defaultId, null, null);
    }

    public AgentProvider(AgentProperties properties, String defaultId,
                         PromptTemplateRenderer templateRenderer, ResourceLoader resourceLoader) {
        this.properties = properties;
        this.defaultId = defaultId;
        this.templateRenderer = templateRenderer;
        this.resourceLoader = resourceLoader;
        this.directSystemPrompt = null;
    }

    /** 单 agent 便捷构造（测试/内嵌用）：按给定 id 注册一个 agent，persona 直接用入参（不加载模板）。 */
    public AgentProvider(String id, String name, String provider, String model,
                         String systemPrompt, String cwd) {
        AgentProperties props = new AgentProperties();
        Map<String, AgentProperties.AgentSpec> map = new LinkedHashMap<>();
        AgentProperties.AgentSpec spec = new AgentProperties.AgentSpec();
        spec.setProvider(provider == null || provider.isBlank() ? "deepseek" : provider);
        spec.setModel(model);
        spec.setCwd(cwd);
        map.put(id, spec);
        props.setAgents(map);
        this.properties = props;
        this.defaultId = id;
        this.templateRenderer = null;
        this.resourceLoader = null;
        this.directSystemPrompt = systemPrompt;
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
        String systemPrompt = directSystemPrompt != null
                ? directSystemPrompt
                : loadConventionPrompt(id);
        return new Agent(id, id, spec.getProvider(), spec.getModel(),
                systemPrompt, spec.getCwd(),
                spec.getEnabledTools(), spec.getDisabledTools(), spec.getCredentialRef());
    }

    /** 按约定加载 classpath {@code prompt/{agentId}.txt}；无模板能力或文件不存在时返回 null。 */
    private String loadConventionPrompt(String agentId) {
        if (resourceLoader == null || templateRenderer == null) {
            return null;
        }
        String path = PROMPT_PATH_PREFIX + agentId + ".txt";
        Resource resource = resourceLoader.getResource("classpath:" + path);
        if (!resource.exists()) {
            return null;
        }
        return templateRenderer.render(path, Map.of());
    }

    /** Agent 不存在（fail loud）。 */
    public static final class AgentNotFoundException extends RuntimeException {
        public AgentNotFoundException(String message) {
            super(message);
        }
    }
}
