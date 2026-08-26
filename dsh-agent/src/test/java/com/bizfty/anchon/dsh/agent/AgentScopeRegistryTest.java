package com.bizfty.anchon.dsh.agent;

import com.bizfty.anchon.dsh.core.model.Agent;
import com.bizfty.anchon.dsh.core.prompt.SystemPromptSection;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.bizfty.anchon.dsh.agent.AgentScopeRegistry.WILDCARD;
import static com.bizfty.anchon.dsh.agent.AgentScopeRegistry.fixedSection;
import com.bizfty.anchon.dsh.agent.AgentScopeRegistry.AgentScopeRegistration;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Agent 作用域注册表测试：注册/组合/遮蔽（同键 shadow、精确优先于通配）/工具过滤收窄。
 */
class AgentScopeRegistryTest {

    private final Agent agent = new Agent("planner", "Planner", "deepseek", "m", null, null,
            List.of("read_file", "echo"), List.of("danger_op"), null);

    private String rendered(AgentScope scope) {
        var ctx = com.bizfty.anchon.dsh.core.prompt.SystemPromptContext.builder().build();
        StringBuilder sb = new StringBuilder();
        for (SystemPromptSection s : scope.extraSections()) {
            sb.append(s.render(ctx));
        }
        return sb.toString();
    }

    @Test
    void emptyRegistryFallsBackToAgentConfig() {
        AgentScopeRegistry registry = new AgentScopeRegistry();
        AgentScope scope = registry.resolve(agent);
        assertTrue(scope.extraSections().isEmpty());
        assertTrue(scope.toolVisibility().test("read_file"));
        assertFalse(scope.toolVisibility().test("danger_op"));
    }

    @Test
    void distinctSectionKeysComposeInOrder() {
        AgentScopeRegistry registry = new AgentScopeRegistry();
        registry.register(AgentScopeRegistration.section("planner", "skills",
                fixedSection(10, "## 技能段\n"), 10));
        registry.register(AgentScopeRegistration.section("planner", "persona",
                fixedSection(0, "## 角色段\n"), 0));
        AgentScope scope = registry.resolve(agent);
        assertEquals(2, scope.extraSections().size());
        String text = rendered(scope);
        assertTrue(text.indexOf("角色段") < text.indexOf("技能段"), "段应按 order 排序");
    }

    @Test
    void sameKeyShadowsByOrder() {
        AgentScopeRegistry registry = new AgentScopeRegistry();
        registry.register(AgentScopeRegistration.section("planner", "guidance",
                fixedSection(5, "旧指导\n"), 5));
        registry.register(AgentScopeRegistration.section("planner", "guidance",
                fixedSection(20, "新指导\n"), 20));
        AgentScope scope = registry.resolve(agent);
        assertEquals(1, scope.extraSections().size(), "同键遮蔽：只剩一个段");
        assertEquals("新指导\n", rendered(scope));
    }

    @Test
    void exactAgentIdBeatsWildcardForShadow() {
        AgentScopeRegistry registry = new AgentScopeRegistry();
        // 通配注册 order 更高，但精确注册应遮蔽它
        registry.register(AgentScopeRegistration.section(WILDCARD, "guidance",
                fixedSection(100, "通配指导\n"), 100));
        registry.register(AgentScopeRegistration.section("planner", "guidance",
                fixedSection(1, "精确指导\n"), 1));
        AgentScope scope = registry.resolve(agent);
        assertEquals("精确指导\n", rendered(scope), "精确 agentId 优先于通配");
        // 其他 agent 仍受通配注册影响
        AgentScope other = registry.resolve(
                new Agent("other", "O", "deepseek", "m", null, null, List.of(), List.of(), null));
        assertEquals("通配指导\n", rendered(other));
    }

    @Test
    void toolFiltersComposeRestrictively() {
        AgentScopeRegistry registry = new AgentScopeRegistry();
        registry.register(AgentScopeRegistration.toolFilter("planner", "no-danger",
                name -> !name.equals("danger_op"), 1));
        registry.register(AgentScopeRegistration.toolFilter(WILDCARD, "read-only",
                name -> !name.equals("write_file"), 1));
        AgentScope scope = registry.resolve(agent);
        // 基础（read_file 可见、danger_op 不可见）AND 两个过滤
        assertTrue(scope.toolVisibility().test("read_file"));
        assertFalse(scope.toolVisibility().test("write_file"), "通配过滤收窄");
        assertFalse(scope.toolVisibility().test("danger_op"));
    }

    @Test
    void toolOnlyRegistrationContributesNoSections() {
        AgentScopeRegistry registry = new AgentScopeRegistry();
        registry.register(AgentScopeRegistration.toolFilter("planner", "f1",
                name -> name.startsWith("x_"), 0));
        AgentScope scope = registry.resolve(agent);
        assertTrue(scope.extraSections().isEmpty());
        assertFalse(scope.toolVisibility().test("read_file"), "过滤后 read_file 不可见");
    }

    @Test
    void registrationValidatesArgs() {
        AgentScopeRegistry registry = new AgentScopeRegistry();
        try {
            registry.register(new AgentScopeRegistry.AgentScopeRegistration("", "k", null, null, 0));
            org.junit.jupiter.api.Assertions.fail("空 agentId 应拒绝");
        } catch (IllegalArgumentException expected) {
            // ok
        }
        assertEquals(0, registry.size());
    }
}
