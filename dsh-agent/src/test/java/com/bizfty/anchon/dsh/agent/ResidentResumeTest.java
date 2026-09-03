package com.bizfty.anchon.dsh.agent;

import com.bizfty.anchon.dsh.core.event.SessionEventBus;
import com.bizfty.anchon.dsh.core.model.MessageRole;
import com.bizfty.anchon.dsh.core.model.Session;
import com.bizfty.anchon.dsh.core.model.SessionId;
import com.bizfty.anchon.dsh.core.prompt.SystemPromptService;
import com.bizfty.anchon.dsh.llm.LlmGateway;
import com.bizfty.anchon.dsh.session.SessionService;
import com.bizfty.anchon.dsh.tool.AgentTool;
import com.bizfty.anchon.dsh.tool.Tool;
import com.bizfty.anchon.dsh.tool.ToolCall;
import com.bizfty.anchon.dsh.tool.ToolContext;
import com.bizfty.anchon.dsh.tool.ToolExecutionPipeline;
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
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * M4-4 resume/身份收编（design §4.3，红线 §6-4c）：
 * executionId/seriesId 收编为 ResidentAgent 对象态；abort 停驻后可 resume（同 executionId 续轮）；
 * 已落库的 step（TOOL 输出）不重放（从安全点续，无重复 seq）。
 */
class ResidentResumeTest {

    private final SessionId sessionId = SessionId.of("sess_resume");
    private final Session session = new Session(sessionId, "续轮", null, "/workspace",
            Instant.now(), Instant.now());

    private static final class ScriptedGateway implements LlmGateway {
        final List<AssistantMessage> responses;
        final AtomicInteger calls = new AtomicInteger();

        ScriptedGateway(AssistantMessage... responses) {
            this.responses = List.of(responses);
        }

        @Override
        public ChatResponse call(List<Message> messages, ChatOptions options) {
            int i = calls.getAndIncrement();
            if (i >= responses.size()) {
                throw new IllegalStateException("脚本响应用尽");
            }
            return new ChatResponse(List.of(new Generation(responses.get(i))));
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

    @Tool(name = "gate", description = "可阻塞工具")
    static class GateTool implements AgentTool {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        @Override
        public String name() {
            return "gate";
        }

        @Override
        public ToolSchema getSchema() {
            return ToolSchema.builder().name(name()).description("gate")
                    .addParameter("text", "string", "文本").required("text").build();
        }

        @Override
        public ToolResult execute(ToolCall call, ToolContext context) {
            entered.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return ToolResult.success("gate: done");
        }
    }

    private AgentLoopService newLoop(LlmGateway gateway, GateTool gateTool,
                                     ResidentAgentRegistry registry, List<String> appendLog) {
        SessionService sessionService = mock(SessionService.class);
        when(sessionService.getSession(sessionId)).thenReturn(session);
        when(sessionService.listMessages(sessionId)).thenReturn(List.of());
        doAnswer(inv -> {
            MessageRole role = inv.getArgument(1);
            String content = inv.getArgument(2);
            String toolCallId = inv.getArgument(3);
            appendLog.add(role + ":" + (toolCallId == null ? "-" : toolCallId)
                    + ":" + (content == null ? "" : content));
            return null;
        }).when(sessionService).append(any(), any(), any(), any(), any(), any());

        ApplicationContext ctx = mock(ApplicationContext.class);
        when(ctx.getBeansOfType(AgentTool.class)).thenReturn(
                gateTool == null ? Map.of() : Map.of("gate", gateTool));
        ToolRegistry toolRegistry = new ToolRegistry(ctx);
        ToolExecutionPipeline pipeline = new ToolExecutionPipeline(toolRegistry, List.of(), List.of(),
                new com.bizfty.anchon.dsh.tool.ToolEventPublisher(new SessionEventBus(), new JsonUtils()),
                new JsonUtils());
        SystemPromptService promptService = new SystemPromptService(List.of());
        AgentProvider agentProvider = new AgentProvider("main", "Archon", "deepseek", "", "", "/workspace");
        AgentLoopProperties props = new AgentLoopProperties(10, 0.7, 200, 4);
        AgentLoopService loop = new AgentLoopService(gateway, sessionService, toolRegistry, pipeline, promptService,
                agentProvider, null, null, null,
                new MessageProjector(new JsonUtils()), new SessionEventBus(), new JsonUtils(), props,
                new com.bizfty.anchon.dsh.compaction.CompactionService(
                        new com.bizfty.anchon.dsh.compaction.CompactionProperties(false, 8000, 40, 2000)),
                null,
                new com.bizfty.anchon.dsh.sandbox.SandboxPolicyService("workspace-write"),
                new ModelRetryPolicy(1, 1, 10), null, null, null, null);
        loop.setResidentAgentRegistry(registry);
        return loop;
    }

    private static AssistantMessage assistantWithToolCall(String id, String name, String argsJson) {
        return AssistantMessage.builder()
                .toolCalls(List.of(new AssistantMessage.ToolCall(id, "function", name, argsJson)))
                .build();
    }

    @Test
    void executionIdentityIsCollectedOntoResidentAgent() throws Exception {
        ResidentAgentRegistry registry = new ResidentAgentRegistry();
        ScriptedGateway gateway = new ScriptedGateway(new AssistantMessage("首答"));
        AgentLoopService loop = newLoop(gateway, null, registry, new ArrayList<>());

        AgentRunResult result = loop.run(AgentRunRequest.builder()
                .sessionId(sessionId).userMessage("你好").agentId("main").build());
        assertEquals("首答", result.content());

        ResidentAgent agent = registry.agent(sessionId);
        ResidentAgent.ResidentState st = agent.residentState();
        assertTrue(st.executionId().startsWith("run-"), "自动生成的 executionId 收编，got=" + st.executionId());
        assertEquals("main", st.agentId());
        assertFalse(st.seriesId().isBlank(), "seriesId 收编");
        registry.release(sessionId);
    }

    @Test
    void abortThenResumeWithSameExecutionIdDoesNotReplayCompletedSteps() throws Exception {
        ResidentAgentRegistry registry = new ResidentAgentRegistry();
        GateTool gate = new GateTool();
        List<String> appendLog = Collections.synchronizedList(new ArrayList<>());
        ScriptedGateway gateway = new ScriptedGateway(
                assistantWithToolCall("tc1", "gate", "{\"text\":\"长跑\"}"),
                new AssistantMessage("续答"));
        AgentLoopService loop = newLoop(gateway, gate, registry, appendLog);
        ResidentAgent agent = registry.agent(sessionId);

        // turn1：工具轮（阻塞中 abort）→ 工具结果已落库（TOOL tc1）→ step 间隙取消
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            CompletableFuture<AgentRunResult> turn1 = CompletableFuture.supplyAsync(() -> loop.run(
                    AgentRunRequest.builder().sessionId(sessionId).userMessage("跑工具").build()), executor);
            assertTrue(gate.entered.await(3, TimeUnit.SECONDS));
            loop.abortSession(sessionId);
            gate.release.countDown();
            try {
                turn1.get(5, TimeUnit.SECONDS);
                fail("turn1 应被取消");
            } catch (ExecutionException e) {
                assertTrue(e.getCause() instanceof AgentCancelledException, "cause=" + e.getCause());
            }
            assertEquals(ResidentPhase.ABORTED, agent.phase());
            long toolLogs = appendLog.stream().filter(l -> l.startsWith("TOOL:tc1")).count();
            assertEquals(1, toolLogs, "abort 前工具结果已落库一次");

            // resume：同 executionId 续轮（不重发原 USER、不重放已完成 TOOL）
            String resumeExecutionId = agent.residentState().executionId();
            assertTrue(resumeExecutionId.startsWith("run-"), "abort 后对象保留 executionId=" + resumeExecutionId);
            Optional<ResidentAgent.ResidentState> resumeState = loop.resumeSession(sessionId);
            assertTrue(resumeState.isPresent(), "aborted 会话可 resume");
            assertEquals(resumeExecutionId, resumeState.get().executionId());

            AgentRunResult turn2 = loop.run(AgentRunRequest.builder()
                    .sessionId(sessionId).userMessage("请继续").executionId(resumeExecutionId).build());
            assertEquals("续答", turn2.content());

            assertEquals(1, appendLog.stream().filter(l -> l.startsWith("TOOL:tc1")).count(),
                    "resume 不重放已完成 step（TOOL tc1 仍只落库一次）");
            assertEquals(ResidentPhase.IDLE, agent.phase(), "resume 完成后对象回 idle");
        } finally {
            executor.shutdownNow();
            registry.release(sessionId);
        }
    }

    @Test
    void resumeReturnsEmptyWhenSessionNotAborted() {
        ResidentAgentRegistry registry = new ResidentAgentRegistry();
        ScriptedGateway gateway = new ScriptedGateway(new AssistantMessage("首答"));
        AgentLoopService loop = newLoop(gateway, null, registry, new ArrayList<>());
        loop.run(AgentRunRequest.builder().sessionId(sessionId).userMessage("你好").build());

        assertTrue(loop.resumeSession(sessionId).isEmpty(), "正常完成（idle）无可 resume 身份");
        registry.release(sessionId);
    }
}
