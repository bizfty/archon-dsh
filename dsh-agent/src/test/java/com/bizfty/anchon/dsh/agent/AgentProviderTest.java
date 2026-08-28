package com.bizfty.anchon.dsh.agent;

import com.bizfty.anchon.dsh.core.model.Agent;
import com.bizfty.anchon.dsh.core.prompt.SimplePromptTemplateRenderer;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 多 Agent 解析测试：按 id 查找、默认 agent、未知 id fail loud、persona 约定模板加载。
 */
class AgentProviderTest {

    private AgentProvider providerWith(String... ids) {
        AgentProperties props = new AgentProperties();
        Map<String, AgentProperties.AgentSpec> map = new LinkedHashMap<>();
        for (String id : ids) {
            AgentProperties.AgentSpec spec = new AgentProperties.AgentSpec();
            spec.setModel("deepseek-chat");
            map.put(id, spec);
        }
        props.setAgents(map);
        return new AgentProvider(props, "main");
    }

    @Test
    void resolvesAgentById() {
        AgentProvider provider = providerWith("main", "planner");
        Agent planner = provider.resolve("planner");
        assertEquals("planner", planner.id());
        assertEquals("deepseek-chat", planner.model());
    }

    @Test
    void defaultAgentResolvesMain() {
        AgentProvider provider = providerWith("main", "planner");
        Agent main = provider.defaultAgent();
        assertEquals("main", main.id());
    }

    @Test
    void unknownIdFailsLoud() {
        AgentProvider provider = providerWith("main");
        AgentProvider.AgentNotFoundException e = assertThrows(AgentProvider.AgentNotFoundException.class,
                () -> provider.resolve("ghost"));
        assertTrue(e.getMessage().contains("ghost"));
    }

    @Test
    void blankIdFallsBackToDefault() {
        AgentProvider provider = providerWith("main");
        assertEquals("main", provider.resolve("").id());
        assertEquals("main", provider.resolve(null).id());
    }

    @Test
    void singleAgentConvenienceConstructor() {
        AgentProvider provider = new AgentProvider("main", "Archon", "deepseek", "m1", "p1", "/ws");
        Agent agent = provider.defaultAgent();
        assertEquals("m1", agent.model());
        assertEquals("p1", agent.systemPrompt());
        assertEquals("/ws", agent.cwd());
    }

    @Test
    void personaLoadsFromConventionTemplate() {
        AgentProperties props = new AgentProperties();
        Map<String, AgentProperties.AgentSpec> map = new LinkedHashMap<>();
        AgentProperties.AgentSpec spec = new AgentProperties.AgentSpec();
        spec.setModel("deepseek-chat");
        map.put("planner", spec);
        props.setAgents(map);

        DefaultResourceLoader loader = new DefaultResourceLoader();
        SimplePromptTemplateRenderer renderer = new SimplePromptTemplateRenderer(loader);
        AgentProvider provider = new AgentProvider(props, "main", renderer, loader);
        Agent planner = provider.resolve("planner");
        assertEquals("test-planner-template: 只规划不实施。\n", planner.systemPrompt());
    }

    @Test
    void missingConventionTemplateLeavesPersonaBlank() {
        // main 无 prompt/main.txt → persona 留空，由 SystemPromptService 回退默认 persona
        AgentProperties props = new AgentProperties();
        Map<String, AgentProperties.AgentSpec> map = new LinkedHashMap<>();
        AgentProperties.AgentSpec spec = new AgentProperties.AgentSpec();
        spec.setModel("deepseek-chat");
        map.put("main", spec);
        props.setAgents(map);

        DefaultResourceLoader loader = new DefaultResourceLoader();
        SimplePromptTemplateRenderer renderer = new SimplePromptTemplateRenderer(loader);
        AgentProvider provider = new AgentProvider(props, "main", renderer, loader);
        Agent agent = provider.defaultAgent();
        assertNull(agent.systemPrompt());
    }
}
