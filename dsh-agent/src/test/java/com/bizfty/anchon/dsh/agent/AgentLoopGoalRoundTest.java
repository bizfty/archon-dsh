package com.bizfty.anchon.dsh.agent;

import com.bizfty.anchon.dsh.core.event.SessionEventBus;
import com.bizfty.anchon.dsh.core.model.MessageRole;
import com.bizfty.anchon.dsh.core.model.Session;
import com.bizfty.anchon.dsh.core.model.SessionId;
import com.bizfty.anchon.dsh.core.model.SessionMessage;
import com.bizfty.anchon.dsh.core.prompt.SystemPromptService;
import com.bizfty.anchon.dsh.goal.Goal;
import com.bizfty.anchon.dsh.goal.GoalService;
import com.bizfty.anchon.dsh.llm.LlmGateway;
import com.bizfty.anchon.dsh.session.SessionService;
import com.bizfty.anchon.dsh.storage.InMemoryStorageBackend;
import com.bizfty.anchon.dsh.storage.StorageService;
import com.bizfty.anchon.dsh.tool.AgentTool;
import com.bizfty.anchon.dsh.tool.ToolExecutionPipeline;
import com.bizfty.anchon.dsh.tool.ToolEventPublisher;
import com.bizfty.anchon.dsh.tool.ToolRegistry;
import com.bizfty.anchon.dsh.util.JsonUtils;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationContext;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Goal 自动续轮集成测试（对齐官方 goal-round-driver）：
 * <ul>
 *   <li>turn 正常结束后，active 且未超限的 goal 自动预留下一轮并继续执行；</li>
 *   <li>达到 maxGoalRounds 时 goal 置为 blocked（round-limit）并停止；</li>
 *   <li>模型把 goal 置为 complete 后不再续轮；</li>
 *   <li>turn 失败（超步数）时不续轮（TURN_ERROR 已发布，异常上抛）。</li>
 * </ul>
 */
class AgentLoopGoalRoundTest {

    private final SessionId sessionId = SessionId.of("sess_goal");
    private final Session session = new Session(sessionId, "目标", null, "/workspace",
            Instant.now(), Instant.now());

    private final class GoalHarness {
        final SessionEventBus bus = new SessionEventBus();
        final List<SessionMessage> appended = new ArrayList<>();
        final List<String> userMessages = new ArrayList<>();
        final GoalService goals;
        final AgentLoopService loop;
        final CountingGateway gateway;

        GoalHarness(CountingGateway gateway, GoalService goals) {
            this.gateway = gateway;
            this.goals = goals;
            SessionService sessionService = mock(SessionService.class);
            when(sessionService.getSession(sessionId)).thenReturn(session);
            when(sessionService.listMessages(sessionId)).thenAnswer(inv -> List.copyOf(appended));
            when(sessionService.append(any(), any(), any(), any(), any(), any())).thenAnswer(inv -> {
                SessionMessage m = new SessionMessage("m" + appended.size(), sessionId,
                        inv.getArgument(1), inv.getArgument(2), inv.getArgument(3),
                        inv.getArgument(4), inv.getArgument(5), appended.size() + 1L, Instant.now());
                appended.add(m);
                if (inv.getArgument(1) == MessageRole.USER) {
                    userMessages.add(inv.getArgument(2));
                }
                return m;
            });
            ApplicationContext ctx = mock(ApplicationContext.class);
            when(ctx.getBeansOfType(AgentTool.class)).thenReturn(Map.of());
            ToolRegistry registry = new ToolRegistry(ctx);
            ToolExecutionPipeline pipeline = new ToolExecutionPipeline(registry, List.of(), List.of(),
                    new ToolEventPublisher(bus, new JsonUtils()), new JsonUtils());
            loop = new AgentLoopService(gateway, sessionService, registry, pipeline,
                    new SystemPromptService(List.of()),
                    new AgentProvider("main", "Archon", "deepseek", "", "", "/workspace"),
                    null, null, null,
                    new MessageProjector(new JsonUtils()), bus, new JsonUtils(),
                    new AgentLoopProperties(10, 0.7, 200, 4),
                    new com.bizfty.anchon.dsh.compaction.CompactionService(
                            new com.bizfty.anchon.dsh.compaction.CompactionProperties(false, 8000, 40, 2000)),
                    null,
                    new com.bizfty.anchon.dsh.sandbox.SandboxPolicyService("workspace-write"),
                    new ModelRetryPolicy(1, 1, 10), null, null, goals, null);
        }
    }

    /** 计数网关：每轮返回纯文本，便于统计模型调用次数。 */
    private static final class CountingGateway implements LlmGateway {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public ChatResponse call(List<Message> messages, ChatOptions options) {
            calls.incrementAndGet();
            return new ChatResponse(List.of(new Generation(new AssistantMessage("进度"))));
        }

        @Override
        public Flux<ChatResponse> stream(List<Message> messages, ChatOptions options) {
            return Flux.just(call(messages, options));
        }

        @Override
        public String defaultModel() {
            return "deepseek-chat";
        }
    }

    private GoalService goalService() {
        @SuppressWarnings("unchecked")
        ObjectProvider<com.bizfty.anchon.dsh.storage.StorageBackend> sp = mock(ObjectProvider.class);
        when(sp.orderedStream()).thenReturn(Stream.of(new InMemoryStorageBackend()));
        return new GoalService(new StorageService(sp));
    }

    @Test
    void autoContinuesUntilRoundLimitThenBlocks() {
        GoalHarness h = new GoalHarness(new CountingGateway(), goalService());
        Goal goal = h.goals.create(sessionId.value(), "完成目标", 2);

        AgentRunResult result = h.loop.run(AgentRunRequest.builder()
                .sessionId(sessionId).userMessage("开始").build());

        // 人类 turn（1 次模型调用）+ 2 轮自动续轮 = 3 次
        assertEquals(3, h.gateway.calls.get(), "应自动续 2 轮");
        assertEquals(2, h.goals.current(sessionId.value()).orElseThrow().roundsStarted());
        Goal after = h.goals.current(sessionId.value()).orElseThrow();
        assertEquals(Goal.PHASE_BLOCKED, after.phase(), "达到上限应 blocked");
        assertEquals("round-limit", after.blockedCode());
        // 续轮提示词应为 <goal_round> 块
        assertTrue(h.userMessages.stream().anyMatch(m -> m.contains("<goal_round>")),
                "应注入 goal_round 续行提示词: " + h.userMessages);
        assertEquals("进度", result.content());
    }

    @Test
    void stopsWhenGoalCompletes() {
        // 第一次调用：人类 turn 正常结束；goal 已被模型置为 complete → 不续轮
        GoalHarness h = new GoalHarness(new CountingGateway(), goalService());
        Goal goal = h.goals.create(sessionId.value(), "短目标", 5);
        h.goals.update(sessionId.value(), goal.id(), goal.revision(), "complete", null, null, null, null);

        h.loop.run(AgentRunRequest.builder().sessionId(sessionId).userMessage("开始").build());

        assertEquals(1, h.gateway.calls.get(), "goal 已 complete → 只跑人类 turn");
        assertFalse(h.userMessages.stream().anyMatch(m -> m.contains("<goal_round>")),
                "complete 后不应注入续轮提示词");
    }

    @Test
    void noContinuationWithoutGoalService() {
        // goalService = null → 退化为单 turn
        CountingGateway gateway = new CountingGateway();
        SessionService sessionService = mock(SessionService.class);
        when(sessionService.getSession(sessionId)).thenReturn(session);
        when(sessionService.listMessages(sessionId)).thenReturn(List.of());
        ApplicationContext ctx = mock(ApplicationContext.class);
        when(ctx.getBeansOfType(AgentTool.class)).thenReturn(Map.of());
        ToolRegistry registry = new ToolRegistry(ctx);
        SessionEventBus bus = new SessionEventBus();
        ToolExecutionPipeline pipeline = new ToolExecutionPipeline(registry, List.of(), List.of(),
                new ToolEventPublisher(bus, new JsonUtils()), new JsonUtils());
        AgentLoopService noGoal = new AgentLoopService(gateway, sessionService, registry, pipeline,
                new SystemPromptService(List.of()),
                new AgentProvider("main", "Archon", "deepseek", "", "", "/workspace"),
                null, null, null,
                new MessageProjector(new JsonUtils()), bus, new JsonUtils(),
                new AgentLoopProperties(10, 0.7, 200, 4),
                new com.bizfty.anchon.dsh.compaction.CompactionService(
                        new com.bizfty.anchon.dsh.compaction.CompactionProperties(false, 8000, 40, 2000)),
                null,
                new com.bizfty.anchon.dsh.sandbox.SandboxPolicyService("workspace-write"),
                new ModelRetryPolicy(1, 1, 10), null, null, null, null);

        noGoal.run(AgentRunRequest.builder().sessionId(sessionId).userMessage("开始").build());
        assertEquals(1, gateway.calls.get(), "无 goal 服务 → 单 turn");
    }

    @Test
    void turnErrorDoesNotContinue() {
        // 每步都返回工具调用 → 撞 max-steps 上限（TURN_ERROR）→ 不续轮，异常上抛
        LlmGateway foreverGateway = new LlmGateway() {
            @Override
            public ChatResponse call(List<Message> messages, ChatOptions options) {
                return new ChatResponse(List.of(new Generation(AssistantMessage.builder()
                        .toolCalls(List.of(new AssistantMessage.ToolCall("c1", "function", "no_such_tool", "{}")))
                        .build())));
            }

            @Override
            public Flux<ChatResponse> stream(List<Message> messages, ChatOptions options) {
                return Flux.just(call(messages, options));
            }

            @Override
            public String defaultModel() {
                return "deepseek-chat";
            }
        };
        GoalService goals = goalService();
        SessionService sessionService = mock(SessionService.class);
        when(sessionService.getSession(sessionId)).thenReturn(session);
        when(sessionService.listMessages(sessionId)).thenReturn(List.of());
        ApplicationContext ctx = mock(ApplicationContext.class);
        when(ctx.getBeansOfType(AgentTool.class)).thenReturn(Map.of());
        ToolRegistry registry = new ToolRegistry(ctx);
        SessionEventBus bus = new SessionEventBus();
        ToolExecutionPipeline pipeline = new ToolExecutionPipeline(registry, List.of(), List.of(),
                new ToolEventPublisher(bus, new JsonUtils()), new JsonUtils());
        AgentLoopService failing = new AgentLoopService(foreverGateway, sessionService, registry, pipeline,
                new SystemPromptService(List.of()),
                new AgentProvider("main", "Archon", "deepseek", "", "", "/workspace"),
                null, null, null,
                new MessageProjector(new JsonUtils()), bus, new JsonUtils(),
                new AgentLoopProperties(2, 0.7, 200, 4),
                new com.bizfty.anchon.dsh.compaction.CompactionService(
                        new com.bizfty.anchon.dsh.compaction.CompactionProperties(false, 8000, 40, 2000)),
                null,
                new com.bizfty.anchon.dsh.sandbox.SandboxPolicyService("workspace-write"),
                new ModelRetryPolicy(1, 1, 10), null, null, goals, null);

        goals.create(sessionId.value(), "失败目标", 5);
        try {
            failing.run(AgentRunRequest.builder().sessionId(sessionId).userMessage("开始").build());
        } catch (AgentLoopException expected) {
            // 超步数 → 不续轮
        }
        Goal after = goals.current(sessionId.value()).orElseThrow();
        assertEquals(0, after.roundsStarted(), "turn 失败不应消耗轮数");
        assertEquals(Goal.PHASE_ACTIVE, after.phase(), "turn 失败不应自动续轮");
        assertFalse(goals.isArmed(sessionId.value()), "turn 失败应 disarm（需显式 resume 恢复）");
    }

    @Test
    void maxTokensDisarmsAndStopsContinuation() {
        // 模型返回 finishReason=length（撞 max-tokens）→ turn 结束 + disarm → 不续轮
        LlmGateway maxTokensGateway = new LlmGateway() {
            @Override
            public ChatResponse call(List<Message> messages, ChatOptions options) {
                return new ChatResponse(List.of(new Generation(
                        new AssistantMessage("输出被截断"),
                        org.springframework.ai.chat.metadata.ChatGenerationMetadata.builder()
                                .finishReason("length").build())));
            }

            @Override
            public Flux<ChatResponse> stream(List<Message> messages, ChatOptions options) {
                return Flux.just(call(messages, options));
            }

            @Override
            public String defaultModel() {
                return "deepseek-chat";
            }
        };
        SessionService sessionService = mock(SessionService.class);
        when(sessionService.getSession(sessionId)).thenReturn(session);
        when(sessionService.listMessages(sessionId)).thenReturn(List.of());
        ApplicationContext ctx = mock(ApplicationContext.class);
        when(ctx.getBeansOfType(AgentTool.class)).thenReturn(Map.of());
        ToolRegistry registry = new ToolRegistry(ctx);
        SessionEventBus bus = new SessionEventBus();
        ToolExecutionPipeline pipeline = new ToolExecutionPipeline(registry, List.of(), List.of(),
                new ToolEventPublisher(bus, new JsonUtils()), new JsonUtils());
        GoalService goals = goalService();
        AgentLoopService loop = new AgentLoopService(maxTokensGateway, sessionService, registry, pipeline,
                new SystemPromptService(List.of()),
                new AgentProvider("main", "Archon", "deepseek", "", "", "/workspace"),
                null, null, null,
                new MessageProjector(new JsonUtils()), bus, new JsonUtils(),
                new AgentLoopProperties(10, 0.7, 200, 4),
                new com.bizfty.anchon.dsh.compaction.CompactionService(
                        new com.bizfty.anchon.dsh.compaction.CompactionProperties(false, 8000, 40, 2000)),
                null,
                new com.bizfty.anchon.dsh.sandbox.SandboxPolicyService("workspace-write"),
                new ModelRetryPolicy(1, 1, 10), null, null, goals, null);

        goals.create(sessionId.value(), "截断目标", 5);
        AgentRunResult result = loop.run(AgentRunRequest.builder()
                .sessionId(sessionId).userMessage("开始").build());

        assertEquals("输出被截断", result.content());
        assertEquals(0, goals.current(sessionId.value()).orElseThrow().roundsStarted(),
                "max-tokens 结束不应续轮/消耗轮数");
        assertFalse(goals.isArmed(sessionId.value()), "max-tokens 后应 disarm");
    }

    @Test
    void resumeReArmsAfterDisarm() {
        GoalService goals = goalService();
        Goal goal = goals.create(sessionId.value(), "恢复目标", 3);
        goals.disarm(sessionId.value());
        assertFalse(goals.isArmed(sessionId.value()), "disarm 后未 armed");

        // 显式 resume → 恢复 armed（自动续行重新启用）
        Goal resumed = goals.update(sessionId.value(), goal.id(), goal.revision(),
                "resume", null, null, null, null);
        assertEquals(Goal.PHASE_ACTIVE, resumed.phase());
        assertTrue(goals.isArmed(sessionId.value()), "resume 后应恢复 armed");
    }

    @Test
    void streamPathAlsoContinues() {
        GoalHarness h = new GoalHarness(new CountingGateway(), goalService());
        h.goals.create(sessionId.value(), "流式目标", 1);

        StringBuilder tokens = new StringBuilder();
        h.loop.stream(AgentRunRequest.builder().sessionId(sessionId).userMessage("开始").build(),
                tokens::append, ignored -> {
                });

        assertEquals(2, h.gateway.calls.get(), "流式路径也应自动续 1 轮");
        Goal after = h.goals.current(sessionId.value()).orElseThrow();
        assertEquals(1, after.roundsStarted());
    }
}
