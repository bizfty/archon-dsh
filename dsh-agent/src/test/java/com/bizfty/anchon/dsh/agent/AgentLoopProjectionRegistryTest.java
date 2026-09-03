package com.bizfty.anchon.dsh.agent;

import com.bizfty.anchon.dsh.core.event.SessionEventBus;
import com.bizfty.anchon.dsh.core.model.MessageRole;
import com.bizfty.anchon.dsh.core.model.Session;
import com.bizfty.anchon.dsh.core.model.SessionId;
import com.bizfty.anchon.dsh.core.model.SessionMessage;
import com.bizfty.anchon.dsh.llm.LlmGateway;
import com.bizfty.anchon.dsh.session.SessionProjectionRegistry;
import com.bizfty.anchon.dsh.session.SessionService;
import com.bizfty.anchon.dsh.session.SessionSurfaceStore;
import com.bizfty.anchon.dsh.session.SurfaceInstruction;
import com.bizfty.anchon.dsh.session.SurfaceOp;
import com.bizfty.anchon.dsh.session.SurfaceProjector;
import com.bizfty.anchon.dsh.tool.AgentTool;
import com.bizfty.anchon.dsh.tool.ToolExecutionPipeline;
import com.bizfty.anchon.dsh.tool.ToolEventPublisher;
import com.bizfty.anchon.dsh.tool.ToolRegistry;
import com.bizfty.anchon.dsh.util.JsonUtils;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.context.ApplicationContext;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * M9 投影 registry 装配契约测试：AgentLoop 装配 SessionProjectionRegistry 后，seam 读路径
 * 以遮蔽区间表物化（docs/design-projection-cache.md §4.3）—— 两次 execute 间指令集不变时
 * loader（listInstructions）只调一次（命中零 DB 重读）；可见序列与 M8 直算路径同构
 * （摘要视图行置头、被遮蔽历史不重发、当前 USER 循环注入一次）。
 */
class AgentLoopProjectionRegistryTest {

    private final SessionId sessionId = SessionId.of("sess_proj_reg_loop");
    private final Session session = new Session(sessionId, "测试", null, "/workspace",
            Instant.now(), Instant.now());

    private SessionMessage m(long seq, MessageRole role, String content) {
        return new SessionMessage("msg_" + seq, sessionId, role, content, null, null, null, seq,
                Instant.now(), false);
    }

    /** 记录模型调用输入的网关（两次响应）。 */
    private static final class TwoStepGateway implements LlmGateway {
        private final AtomicInteger calls = new AtomicInteger();
        private final List<List<Message>> recorded = new ArrayList<>();

        List<List<Message>> recorded() {
            return recorded;
        }

        @Override
        public ChatResponse call(List<Message> messages, ChatOptions options) {
            int i = calls.getAndIncrement();
            recorded.add(List.copyOf(messages));
            return new ChatResponse(List.of(new Generation(new AssistantMessage("done" + (i + 1)))));
        }

        @Override
        public Flux<ChatResponse> stream(List<Message> messages, ChatOptions options) {
            int i = calls.getAndIncrement();
            recorded.add(List.copyOf(messages));
            return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("done" + (i + 1))))));
        }

        @Override
        public String defaultModel() {
            return "deepseek-chat";
        }
    }

    private static com.bizfty.anchon.dsh.compaction.CompactionService disabledCompaction() {
        return new com.bizfty.anchon.dsh.compaction.CompactionService(
                new com.bizfty.anchon.dsh.compaction.CompactionProperties(false, 8000, 40, 2000));
    }

    private AgentLoopService newLoop(LlmGateway gateway,
                                     SessionService sessionService,
                                     SessionSurfaceStore surfaceStore,
                                     SessionEventBus bus,
                                     SessionProjectionRegistry registry) {
        ApplicationContext ctx = mock(ApplicationContext.class);
        when(ctx.getBeansOfType(AgentTool.class)).thenReturn(Map.of());
        ToolRegistry toolRegistry = new ToolRegistry(ctx);
        ToolExecutionPipeline pipeline = new ToolExecutionPipeline(toolRegistry, List.of(), List.of(),
                new ToolEventPublisher(bus, new JsonUtils()), new JsonUtils());
        AgentLoopService loop = new AgentLoopService(gateway, sessionService, toolRegistry, pipeline,
                new com.bizfty.anchon.dsh.core.prompt.SystemPromptService(List.of()),
                new com.bizfty.anchon.dsh.agent.AgentProvider("main", "Archon", "deepseek", "", "", "/workspace"),
                null, null, null,
                new MessageProjector(new JsonUtils()), bus, new JsonUtils(),
                new AgentLoopProperties(10, 0.7, 200, 40), disabledCompaction(),
                null, // 无压缩边界存储 → boundary=0
                new com.bizfty.anchon.dsh.sandbox.SandboxPolicyService("workspace-write"),
                new ModelRetryPolicy(1, 1, 10), null, null, null, null);
        loop.setSurfaceProjector(new SurfaceProjector(new JsonUtils()));
        loop.setSessionSurfaceStore(surfaceStore);
        if (registry != null) {
            loop.setProjectionRegistry(registry);
        }
        return loop;
    }

    private static List<String> userTexts(List<Message> sent) {
        return sent.stream()
                .filter(msg -> msg instanceof UserMessage)
                .map(msg -> ((UserMessage) msg).getText())
                .toList();
    }

    @Test
    void registryHitLoadsInstructionsOnceAcrossTurns() {
        // 库历史：seq1..4（seq1..3 被 REPLACE_HEAD 遮蔽）
        List<SessionMessage> persisted = new ArrayList<>(List.of(
                m(1, MessageRole.USER, "旧问题"),
                m(2, MessageRole.ASSISTANT, "旧回答"),
                m(3, MessageRole.USER, "更旧尾巴"),
                m(4, MessageRole.ASSISTANT, "保留回答")));
        SessionService sessionService = mock(SessionService.class);
        when(sessionService.getSession(sessionId)).thenReturn(session);
        when(sessionService.listMessages(sessionId)).thenAnswer(inv -> List.copyOf(persisted));
        when(sessionService.append(any(), any(), any(), any(), any(), any())).thenAnswer(inv -> {
            SessionMessage nm = new SessionMessage("msg_" + (persisted.size() + 1), sessionId,
                    inv.getArgument(1), inv.getArgument(2), inv.getArgument(3),
                    inv.getArgument(4), inv.getArgument(5), persisted.size() + 1L, Instant.now());
            persisted.add(nm);
            return nm;
        });

        AtomicInteger loads = new AtomicInteger();
        SessionSurfaceStore surfaceStore = mock(SessionSurfaceStore.class);
        when(surfaceStore.listInstructions(sessionId)).thenAnswer(inv -> {
            loads.incrementAndGet();
            return List.of(new SurfaceInstruction(1, SurfaceOp.REPLACE_HEAD, 1, 3L,
                    "（历史压缩摘要）\n早前对话摘要", "{\"reason\":\"compaction\"}"));
        });

        SessionEventBus bus = new SessionEventBus();
        SessionProjectionRegistry registry =
                new SessionProjectionRegistry(new SurfaceProjector(new JsonUtils()), bus, 64, false);
        TwoStepGateway gateway = new TwoStepGateway();
        AgentLoopService loop = newLoop(gateway, sessionService, surfaceStore, bus, registry);

        loop.run(AgentRunRequest.builder().sessionId(sessionId).userMessage("hi").build());
        loop.run(AgentRunRequest.builder().sessionId(sessionId).userMessage("again").build());

        // 两次 execute，指令集不变 → registry 只装载一次（第二次命中遮蔽区间表）
        assertEquals(1, loads.get(), "registry 命中：跨 turn 指令不变只 loader 一次");
        assertEquals(2, gateway.recorded().size());
        // run1 可见 USER = [摘要视图行, hi]；run2 历史含 run1 的 hi（seq5 未遮蔽保留）+ 当前 again。
        List<String> first = userTexts(gateway.recorded().get(0));
        assertEquals(2, first.size(), "run1 可见 USER = 摘要视图行 + 当前问题");
        assertEquals("（历史压缩摘要）\n早前对话摘要", first.get(0), "摘要视图行应置头（registry 快照路径）");
        assertEquals("hi", first.get(1));
        List<String> second = userTexts(gateway.recorded().get(1));
        assertEquals(3, second.size(), "run2 可见 USER = 摘要视图行 + 历史 hi + 当前 again");
        assertEquals("（历史压缩摘要）\n早前对话摘要", second.get(0));
        assertEquals("hi", second.get(1));
        assertEquals("again", second.get(2));
        String all = gateway.recorded().get(0).stream()
                .map(Message::getText).reduce("", (a, b) -> a + "\n" + b);
        assertTrue(!all.contains("旧问题"), "被遮蔽历史不得重发");
        assertTrue(all.contains("保留回答"), "未遮蔽尾部应可见");
    }

    @Test
    void registryAbsentFallsBackToDirectComputation() {
        // 未装配 registry（M8 直算路径契约锁）：输出同构，仅 loader 每 turn 都调。
        List<SessionMessage> persisted = new ArrayList<>(List.of(
                m(1, MessageRole.USER, "旧问题"),
                m(2, MessageRole.ASSISTANT, "旧回答"),
                m(4, MessageRole.ASSISTANT, "保留回答")));
        SessionService sessionService = mock(SessionService.class);
        when(sessionService.getSession(sessionId)).thenReturn(session);
        when(sessionService.listMessages(sessionId)).thenAnswer(inv -> List.copyOf(persisted));
        when(sessionService.append(any(), any(), any(), any(), any(), any())).thenAnswer(inv -> {
            SessionMessage nm = new SessionMessage("msg_" + (persisted.size() + 1), sessionId,
                    inv.getArgument(1), inv.getArgument(2), inv.getArgument(3),
                    inv.getArgument(4), inv.getArgument(5), persisted.size() + 1L, Instant.now());
            persisted.add(nm);
            return nm;
        });
        AtomicInteger loads = new AtomicInteger();
        SessionSurfaceStore surfaceStore = mock(SessionSurfaceStore.class);
        when(surfaceStore.listInstructions(sessionId)).thenAnswer(inv -> {
            loads.incrementAndGet();
            return List.of(new SurfaceInstruction(1, SurfaceOp.REPLACE_HEAD, 1, 2L,
                    "（历史压缩摘要）\n早前对话摘要", "{\"reason\":\"compaction\"}"));
        });

        SessionEventBus bus = new SessionEventBus();
        TwoStepGateway gateway = new TwoStepGateway();
        AgentLoopService loop = newLoop(gateway, sessionService, surfaceStore, bus, null);

        loop.run(AgentRunRequest.builder().sessionId(sessionId).userMessage("hi").build());
        loop.run(AgentRunRequest.builder().sessionId(sessionId).userMessage("again").build());

        assertTrue(loads.get() >= 2, "未装配 registry：直算路径每 seam 都读指令（无缓存，M8 现状口径）");
        List<String> first = userTexts(gateway.recorded().get(0));
        assertEquals(2, first.size(), "run1 可见 USER = 摘要视图行 + 当前问题（直算同构）");
        assertEquals("（历史压缩摘要）\n早前对话摘要", first.get(0));
        List<String> second = userTexts(gateway.recorded().get(1));
        assertEquals("（历史压缩摘要）\n早前对话摘要", second.get(0), "直算与 registry 快照输出同构");
    }
}
