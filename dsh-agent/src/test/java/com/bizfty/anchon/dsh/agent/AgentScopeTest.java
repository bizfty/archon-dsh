package com.bizfty.anchon.dsh.agent;

import com.bizfty.anchon.dsh.core.model.Agent;
import com.bizfty.anchon.dsh.core.prompt.SystemPromptContext;
import com.bizfty.anchon.dsh.core.prompt.SystemPromptSection;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Agent 作用域测试：按 agent 推导可见性、额外段、自定义作用域。
 */
class AgentScopeTest {

    @Test
    void forAgentDerivesToolVisibility() {
        Agent agent = new Agent("limited", "L", "deepseek", "m", null, null,
                List.of("echo"), List.of("danger_op"), null);
        AgentScope scope = AgentScope.forAgent(agent);
        assertTrue(scope.toolVisibility().test("echo"));
        assertFalse(scope.toolVisibility().test("danger_op"));
        assertFalse(scope.toolVisibility().test("other"));
        assertTrue(scope.extraSections().isEmpty());
    }

    @Test
    void customScopeHoldsSectionsAndPredicate() {
        SystemPromptSection section = new SystemPromptSection() {
            @Override
            public int order() {
                return 5;
            }

            @Override
            public String render(SystemPromptContext context) {
                return "## 自定义段\n";
            }
        };
        AgentScope scope = AgentScope.of(List.of(section), name -> name.startsWith("x_"));
        assertEquals(1, scope.extraSections().size());
        assertTrue(scope.toolVisibility().test("x_tool"));
        assertFalse(scope.toolVisibility().test("y_tool"));
    }

    @Test
    void nullSafeDefaults() {
        AgentScope scope = AgentScope.of(null, null);
        assertTrue(scope.extraSections().isEmpty());
        assertTrue(scope.toolVisibility().test("anything"));
    }
}
