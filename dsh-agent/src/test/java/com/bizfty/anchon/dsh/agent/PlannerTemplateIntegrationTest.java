package com.bizfty.anchon.dsh.agent;

import com.bizfty.anchon.dsh.core.model.Agent;
import com.bizfty.anchon.dsh.core.prompt.SimplePromptTemplateRenderer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 生产装配级验证：在真实 Spring 上下文（AgentProvider + SimplePromptTemplateRenderer 自动装配、
 * ResourceLoader 由容器注入）中，验证 planner 的 systemPrompt 按约定从 classpath 模板
 * {@code prompt/planner.txt} 加载（非配置内联）。
 * <p>
 * 配置面通过内联 properties 注入（等价 dsh-boot/application.yml 的 dsh.agents.*）。
 * <p>
 * 注意 classpath 解析：测试运行时 {@code test-classes} 排在 {@code classes} 前，
 * 因此本测试加载到的是 {@code src/test/resources/prompt/planner.txt}（测试模板）；
 * 生产部署无 test 资源，加载的是 {@code src/main/resources/prompt/planner.txt}
 * （内容已由部署产物检查确认：dsh-agent/target/classes/prompt/planner.txt =
 * "你是规划 Agent（Planner）。你只做规划、拆解任务与制定步骤，不实现代码、不执行操作。输出清晰可执行的分步计划。"）。
 */
@SpringBootTest(classes = PlannerTemplateIntegrationTest.TestConfig.class,
        properties = {
                "dsh.agents.main.provider=deepseek",
                "dsh.agents.main.model=deepseek-chat",
                "dsh.agents.planner.provider=deepseek",
                "dsh.agents.planner.model=deepseek-chat"
        })
class PlannerTemplateIntegrationTest {

    @SpringBootConfiguration
    @Import({AgentProvider.class, SimplePromptTemplateRenderer.class})
    static class TestConfig {
    }

    @Autowired
    private AgentProvider agentProvider;

    @Test
    void plannerSystemPromptLoadsFromClasspathTemplate() {
        Agent planner = agentProvider.resolve("planner");
        assertEquals("planner", planner.id());
        // 来自 classpath 模板（test classpath 下为测试模板），而非配置内联
        assertEquals("test-planner-template: 只规划不实施。\n", planner.systemPrompt());
    }

    @Test
    void mainAgentWithoutTemplateFallsBackToNullPersona() {
        // main 无 prompt/main.txt → persona 为 null，由 SystemPromptService 回退默认 persona
        assertNull(agentProvider.defaultAgent().systemPrompt());
    }
}
