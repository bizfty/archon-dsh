package com.bizfty.anchon.dsh.agent;

import com.bizfty.anchon.dsh.core.event.SessionEventBus;
import com.bizfty.anchon.dsh.core.model.MessageRole;
import com.bizfty.anchon.dsh.core.model.Session;
import com.bizfty.anchon.dsh.core.model.SessionId;
import com.bizfty.anchon.dsh.core.model.SessionMessage;
import com.bizfty.anchon.dsh.llm.LlmGateway;
import com.bizfty.anchon.dsh.session.SessionService;
import com.bizfty.anchon.dsh.session.SessionSurfaceStore;
import com.bizfty.anchon.dsh.session.SurfaceInstruction;
import com.bizfty.anchon.dsh.session.SurfaceProjector;
import com.bizfty.anchon.dsh.session.SurfaceOp;
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
 * M8 surface seam 契约测试：装配 SurfaceProjector + SessionSurfaceStore 后，executeInner
 * 未压缩 turn 走 seam 读路径 —— 模型收到的可见序列由 surface 指令决定（REPLACE_HEAD 摘要视图行
 * 置头、被遮蔽历史不重发、当前 USER 由循环注入一次）。
 */
class AgentLoopSurfaceSeamTest {

    private final SessionId sessionId = SessionId.of("sess_seam_test");
    private final Session session = new Session(sessionId, "测试", null, "/workspace",
            Instant.now(), Instant.now());

    private SessionMessage m(long seq, MessageRole role, String content) {
        return new SessionMessage("msg_" + seq, sessionId, role, content, null, null, null, seq,
                Instant.now(), false);
    }

    /** 记录模型调用输入的网关。 */
    private static final class RecordingGateway implements LlmGateway {
        private final List<AssistantMessage> responses;
        private final AtomicInteger calls = new AtomicInteger();
        private List<Message> lastMessages;

        RecordingGateway(AssistantMessage... responses) {
            this.responses = List.of(responses);
        }

        List<Message> lastMessages() {
            return lastMessages;
        }

        @Override
        public ChatResponse call(List<Message> messages, ChatOptions options) {
            lastMessages = messages;
            int i = calls.getAndIncrement();
            return new ChatResponse(List.of(new Generation(responses.get(i))));
        }

        @Override
        public Flux<ChatResponse> stream(List<Message> messages, ChatOptions options) {
            lastMessages = messages;
            int i = calls.getAndIncrement();
            return Flux.just(new ChatResponse(List.of(new Generation(responses.get(i)))));
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

    @Test
    void surfaceSeamShapesModelVisibleHistory() {
        // 库历史：seq1..4 旧消息（seq1..3 将被压缩遮蔽）
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

        SessionSurfaceStore surfaceStore = mock(SessionSurfaceStore.class);
        when(surfaceStore.listInstructions(sessionId)).thenReturn(List.of(
                new SurfaceInstruction(1, SurfaceOp.REPLACE_HEAD, 1, 3L,
                        "（历史压缩摘要）\n早前对话摘要", "{\"reason\":\"compaction\"}")));

        RecordingGateway gateway = new RecordingGateway(assistantText("done"));
        SessionEventBus bus = new SessionEventBus();
        ApplicationContext ctx = mock(ApplicationContext.class);
        when(ctx.getBeansOfType(AgentTool.class)).thenReturn(Map.of());
        ToolRegistry registry = new ToolRegistry(ctx);
        ToolExecutionPipeline pipeline = new ToolExecutionPipeline(registry, List.of(), List.of(),
                new ToolEventPublisher(bus, new JsonUtils()), new JsonUtils());

        AgentLoopService loop = new AgentLoopService(gateway, sessionService, registry, pipeline,
                new com.bizfty.anchon.dsh.core.prompt.SystemPromptService(List.of()),
                new com.bizfty.anchon.dsh.agent.AgentProvider("main", "Archon", "deepseek", "", "", "/workspace"),
                null, null, null,
                new MessageProjector(new JsonUtils()), bus, new JsonUtils(),
                new AgentLoopProperties(10, 0.7, 200, 40), disabledCompaction(),
                null, // 无压缩边界存储 → boundary=0
                new com.bizfty.anchon.dsh.sandbox.SandboxPolicyService("workspace-write"),
                new ModelRetryPolicy(1, 1, 10), null, null, null, null);
        // 装配 seam（M8）
        loop.setSurfaceProjector(new SurfaceProjector(new JsonUtils()));
        loop.setSessionSurfaceStore(surfaceStore);

        loop.run(AgentRunRequest.builder().sessionId(sessionId).userMessage("hi").build());

        // 模型收到的历史：system + [摘要视图行(USER), 保留 seq4] + 当前 USER(hi)
        List<Message> sent = gateway.lastMessages();
        List<String> userTexts = sent.stream()
                .filter(msg -> msg instanceof UserMessage)
                .map(msg -> ((UserMessage) msg).getText())
                .toList();
        assertEquals(2, userTexts.size(), "可见 USER = 摘要视图行 + 当前问题");
        assertEquals("（历史压缩摘要）\n早前对话摘要", userTexts.get(0), "摘要视图行应置头");
        assertEquals("hi", userTexts.get(1));
        String all = sent.stream().map(Message::getText).reduce("", (a, b) -> a + "\n" + b);
        assertTrue(!all.contains("旧问题"), "被遮蔽历史不得重发");
        assertTrue(all.contains("保留回答"), "未遮蔽尾部应可见");
    }

    private static AssistantMessage assistantText(String text) {
        return new AssistantMessage(text);
    }
}
