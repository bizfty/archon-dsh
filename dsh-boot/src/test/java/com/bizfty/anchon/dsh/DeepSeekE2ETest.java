package com.bizfty.anchon.dsh;

import com.bizfty.anchon.dsh.agent.AgentLoopService;
import com.bizfty.anchon.dsh.agent.AgentRunRequest;
import com.bizfty.anchon.dsh.agent.AgentRunResult;
import com.bizfty.anchon.dsh.core.model.Session;
import com.bizfty.anchon.dsh.core.model.SessionId;
import com.bizfty.anchon.dsh.goal.GoalService;
import com.bizfty.anchon.dsh.session.SessionService;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实 DeepSeek API 端到端测试（对应 DSH 的 e2e 自跳过模式）。
 * <p>
 * 无 DEEPSEEK_API_KEY 环境变量时自动跳过（假死测试不因缺 key 失败）；
 * 有 key 时验证真实模型调用 + 工具执行闭环。
 */
@SpringBootTest
class DeepSeekE2ETest {

    private static final String KEY_ENV = "DEEPSEEK_API_KEY";

    @Autowired
    private AgentLoopService agentLoopService;

    @Autowired
    private SessionService sessionService;

    @BeforeAll
    static void requireKey() {
        Assumptions.assumeTrue(System.getenv(KEY_ENV) != null && !System.getenv(KEY_ENV).isBlank(),
                "缺少 " + KEY_ENV + "，跳过真实 API 测试");
    }

    @DynamicPropertySource
    static void apiKey(DynamicPropertyRegistry registry) {
        String key = System.getenv(KEY_ENV);
        if (key != null && !key.isBlank()) {
            registry.add("spring.ai.openai.api-key", () -> key);
        }
    }

    @Test
    void bashToolRealRoundTrip() {
        Session session = sessionService.createSession("e2e-bash", "deepseek-chat", null);
        AgentRunResult result = agentLoopService.run(AgentRunRequest.builder()
                .sessionId(session.id())
                .userMessage("用 bash 运行 pwd 并告诉我当前目录")
                .build());

        assertNotNull(result);
        assertTrue(result.toolCalls() >= 1, "模型应调用 bash 工具");
        assertTrue(result.content() != null && !result.content().isBlank());
        assertTrue(result.content().contains("/"), "回答应包含路径: " + result.content());
    }

    @Test
    void todoWriteToolRealRoundTrip() {
        Session session = sessionService.createSession("e2e-todo", "deepseek-chat", null);
        AgentRunResult result = agentLoopService.run(AgentRunRequest.builder()
                .sessionId(session.id())
                .userMessage("用 todo_write 记录一条待办：端到端测试完成")
                .build());

        assertNotNull(result);
        assertTrue(result.toolCalls() >= 1, "模型应调用 todo_write 工具");
        assertTrue(result.content() != null && !result.content().isBlank());
    }

    @Test
    void plannerAgentUsesPlannerPersona() {
        Session session = sessionService.createSession("e2e-planner", "deepseek-chat", null);
        AgentRunResult result = agentLoopService.run(AgentRunRequest.builder()
                .sessionId(session.id())
                .agentId("planner")
                .userMessage("请拆解任务：为项目添加用户登录功能")
                .build());

        assertNotNull(result);
        assertTrue(result.content() != null && !result.content().isBlank());
    }

    @Autowired
    private GoalService goalService;

    @Test
    void goalToolRealRoundTrip() {
        Session session = sessionService.createSession("e2e-goal", "deepseek-chat", null);
        AgentRunResult result = agentLoopService.run(AgentRunRequest.builder()
                .sessionId(session.id())
                .userMessage("用 create_goal 创建一个目标：完成 DeepSeek API 回归验证（maxGoalRounds 3），然后报告")
                .build());

        assertNotNull(result);
        assertTrue(result.content() != null && !result.content().isBlank());
        // create_goal 工具应已持久化会话目标
        assertTrue(goalService.current(session.id().value()).isPresent(),
                "create_goal 应持久化目标");
        assertTrue(goalService.current(session.id().value())
                .map(g -> g.objective().contains("DeepSeek API 回归验证")).orElse(false),
                "目标文本应匹配: " + goalService.current(session.id().value()));
    }

    @Test
    void manualCompactRealRoundTrip() {
        Session session = sessionService.createSession("e2e-compact", "deepseek-chat", null);
        // 先产生真实历史（bash 工具调用 + 回答）
        agentLoopService.run(AgentRunRequest.builder()
                .sessionId(session.id())
                .userMessage("用 bash 运行 echo hello-compact")
                .build());
        // /compact 手动压缩（不经过模型 turn）
        String outcome = agentLoopService.manualCompact(session.id());
        assertTrue(outcome.contains("已压缩"), "手动压缩应报告: " + outcome);
        // 摘要已持久化为 USER 消息
        boolean summaryPersisted = sessionService.listMessages(session.id()).stream()
                .anyMatch(m -> m.content() != null && m.content().contains("（历史压缩摘要）"));
        assertTrue(summaryPersisted, "压缩摘要应持久化到会话日志");
        // 后续普通 turn 仍可继续（回放从边界起播）
        AgentRunResult followUp = agentLoopService.run(AgentRunRequest.builder()
                .sessionId(session.id())
                .userMessage("确认你还正常工作，回复：ok")
                .build());
        assertTrue(followUp.content() != null && !followUp.content().isBlank());
    }
}
