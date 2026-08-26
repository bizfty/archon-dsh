package com.bizfty.anchon.dsh.agent;

import com.bizfty.anchon.dsh.core.model.Agent;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 多 Agent 解析测试：按 id 查找、默认 agent、未知 id fail loud。
 */
class AgentProviderTest {

    private AgentProvider providerWith(String... ids) {
        AgentProperties props = new AgentProperties();
        Map<String, AgentProperties.AgentSpec> map = new LinkedHashMap<>();
        for (String id : ids) {
            AgentProperties.AgentSpec spec = new AgentProperties.AgentSpec();
            spec.setModel("deepseek-chat");
            spec.setSystemPrompt(id + " persona");
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
        assertEquals("planner persona", planner.systemPrompt());
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
}
