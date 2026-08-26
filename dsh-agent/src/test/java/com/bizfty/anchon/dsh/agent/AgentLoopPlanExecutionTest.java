package com.bizfty.anchon.dsh.agent;

import com.bizfty.anchon.dsh.compaction.CompactionProperties;
import com.bizfty.anchon.dsh.compaction.CompactionService;
import com.bizfty.anchon.dsh.core.model.Session;
import com.bizfty.anchon.dsh.core.model.SessionId;
import com.bizfty.anchon.dsh.core.model.SessionMessage;
import com.bizfty.anchon.dsh.core.prompt.SystemPromptService;
import com.bizfty.anchon.dsh.llm.LlmGateway;
import com.bizfty.anchon.dsh.plan.PlanEntity;
import com.bizfty.anchon.dsh.plan.PlanService;
import com.bizfty.anchon.dsh.plan.PlanStepEntity;
import com.bizfty.anchon.dsh.session.SessionService;
import com.bizfty.anchon.dsh.tool.AgentTool;
import com.bizfty.anchon.dsh.tool.Tool;
import com.bizfty.anchon.dsh.tool.ToolCall;
import com.bizfty.anchon.dsh.tool.ToolContext;
import com.bizfty.anchon.dsh.tool.ToolExecutionPipeline;
import com.bizfty.anchon.dsh.tool.ToolEventPublisher;
import com.bizfty.anchon.dsh.tool.ToolRegistry;
import com.bizfty.anchon.dsh.tool.ToolResult;
import com.bizfty.anchon.dsh.tool.ToolSchema;
import com.bizfty.anchon.dsh.util.JsonUtils;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.context.ApplicationContext;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 计划步骤 ↔ 工具调用 关联表集成测试：
 * <ul>
 *   <li>会话存在 active 计划时，turn 内工具调用以当前下一步步骤 id 写入
 *       {@code plan_step_execution}（首个人类 turn 与 goal 续轮一致）；</li>
 *   <li>无计划/无下一步时（普通聊天）不写关联行，工具仍正常执行。</li>
 * </ul>
 */
class AgentLoopPlanExecutionTest {

    private final SessionId sessionId = SessionId.of("sess_plan_exec");
    private final Session session = new Session(sessionId, "计划执行", null, "/workspace",
            Instant.now(), Instant.now());

    @Tool(name = "echo", description = "回显")
    static class EchoTool implements AgentTool {
        private final AtomicInteger executed = new AtomicInteger();

        @Override
        public String name() {
            return "echo";
        }

        @Override
        public ToolSchema getSchema() {
            return ToolSchema.builder().name(name()).description("").build();
        }

        @Override
        public ToolResult execute(ToolCall call, ToolContext context) {
            executed.incrementAndGet();
            return ToolResult.success("echo:" + call.getString("msg", "?"));
        }
    }

    /** 脚本网关：第一个响应带 1 个工具调用，第二个响应为最终文本。 */
    private LlmGateway gatewayWithToolCall(AssistantMessage.ToolCall call, String finalText) {
        return new LlmGateway() {
            private final AtomicInteger n = new AtomicInteger();

            @Override
            public ChatResponse call(List<Message> messages, ChatOptions options) {
                int i = n.getAndIncrement();
                if (i == 0) {
                    return new ChatResponse(List.of(new Generation(
                            AssistantMessage.builder().toolCalls(List.of(call)).build())));
                }
                return new ChatResponse(List.of(new Generation(new AssistantMessage(finalText))));
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
    }

    private AgentLoopService loop(LlmGateway gateway, EchoTool tool, PlanService planService) {
        SessionService sessionService = mock(SessionService.class);
        when(sessionService.getSession(sessionId)).thenReturn(session);
        when(sessionService.listMessages(sessionId)).thenReturn(List.of());
        when(sessionService.append(any(), any(), any(), any(), any(), any())).thenAnswer(inv -> {
            SessionMessage m = new SessionMessage("m", sessionId,
                    inv.getArgument(1), inv.getArgument(2), inv.getArgument(3),
                    inv.getArgument(4), inv.getArgument(5), 1L, Instant.now());
            return m;
        });
        ApplicationContext ctx = mock(ApplicationContext.class);
        when(ctx.getBeansOfType(AgentTool.class)).thenReturn(Map.of("echo", tool));
        ToolRegistry registry = new ToolRegistry(ctx);
        com.bizfty.anchon.dsh.core.event.SessionEventBus bus = new com.bizfty.anchon.dsh.core.event.SessionEventBus();
        ToolExecutionPipeline pipeline = new ToolExecutionPipeline(registry, List.of(), List.of(),
                new ToolEventPublisher(bus, new JsonUtils()), new JsonUtils());
        CompactionService compaction = new CompactionService(new CompactionProperties(false, 8000, 40, 2000));
        return new AgentLoopService(gateway, sessionService, registry, pipeline,
                new SystemPromptService(List.of()),
                new AgentProvider("main", "Archon", "deepseek", "", "", "/workspace"),
                null, null, null,
                new MessageProjector(new JsonUtils()), bus, new JsonUtils(),
                new AgentLoopProperties(10, 0.7, 200, 4), compaction,
                null, new com.bizfty.anchon.dsh.sandbox.SandboxPolicyService("workspace-write"),
                new ModelRetryPolicy(1, 1, 10), null, null, null, planService);
    }

    /** active 计划 + 下一步 "step-a" 的 PlanService mock。 */
    private PlanService activePlanService() {
        PlanService planService = mock(PlanService.class);
        PlanEntity plan = mock(PlanEntity.class);
        when(plan.getId()).thenReturn("plan-1");
        when(plan.getStatus()).thenReturn("active");
        PlanStepEntity stepA = mock(PlanStepEntity.class);
        when(stepA.getId()).thenReturn("step-a");
        PlanService.PlanDetail detail = new PlanService.PlanDetail(plan, List.of(stepA), List.of());
        when(planService.currentPlan(any(SessionId.class))).thenReturn(Optional.of(detail));
        when(planService.nextSteps("plan-1")).thenReturn(List.of(stepA));
        return planService;
    }

    @Test
    void toolCallsAreRecordedAgainstCurrentPlanStep() {
        PlanService planService = activePlanService();
        EchoTool tool = new EchoTool();
        AssistantMessage.ToolCall call = new AssistantMessage.ToolCall("call_1", "function",
                "echo", "{\"msg\":\"hi\"}");
        AgentLoopService loop = loop(gatewayWithToolCall(call, "final"), tool, planService);

        AgentRunResult result = loop.run(AgentRunRequest.builder()
                .sessionId(sessionId).userMessage("go").build());

        assertEquals("final", result.content());
        assertEquals(1, result.toolCalls());
        assertEquals(1, tool.executed.get());
        // 首个人类 turn 也注入下一步步骤 id → 工具调用写入关联表
        verify(planService).recordExecution(eq("plan-1"), eq("step-a"), eq(sessionId),
                eq("echo"), anyString(), eq("call_1"), eq("ok"));
    }

    @Test
    void noPlanMeansNoAssociationRows() {
        PlanService planService = mock(PlanService.class);
        when(planService.currentPlan(any(SessionId.class))).thenReturn(Optional.empty());
        EchoTool tool = new EchoTool();
        AssistantMessage.ToolCall call = new AssistantMessage.ToolCall("call_1", "function",
                "echo", "{\"msg\":\"hi\"}");
        AgentLoopService loop = loop(gatewayWithToolCall(call, "final"), tool, planService);

        AgentRunResult result = loop.run(AgentRunRequest.builder()
                .sessionId(sessionId).userMessage("go").build());

        assertEquals("final", result.content());
        assertEquals(1, tool.executed.get(), "无计划时工具仍正常执行");
        verify(planService, never()).recordExecution(any(), any(), any(), any(), any(), any(), any());
    }
}
