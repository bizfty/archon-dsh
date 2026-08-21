package com.example.dsh.agent;

import com.example.dsh.compaction.CompactionProperties;
import com.example.dsh.compaction.CompactionService;
import com.example.dsh.core.event.SessionEventBus;
import com.example.dsh.core.model.MessageRole;
import com.example.dsh.core.model.Session;
import com.example.dsh.core.model.SessionId;
import com.example.dsh.core.model.SessionMessage;
import com.example.dsh.core.prompt.SystemPromptService;
import com.example.dsh.llm.LlmGateway;
import com.example.dsh.session.SessionService;
import com.example.dsh.tool.AgentTool;
import com.example.dsh.tool.Tool;
import com.example.dsh.tool.ToolCall;
import com.example.dsh.tool.ToolContext;
import com.example.dsh.tool.ToolExecutionPipeline;
import com.example.dsh.tool.ToolEventPublisher;
import com.example.dsh.tool.ToolRegistry;
import com.example.dsh.tool.ToolResult;
import com.example.dsh.tool.ToolSchema;
import com.example.dsh.util.JsonUtils;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * per-agent 工具限制测试：受限工具从回调/schema 排除、即使模型硬调也拒绝执行。
 */
class AgentLoopRestrictionTest {

    private final SessionId sessionId = SessionId.of("sess_restrict");
    private final Session session = new Session(sessionId, "限制", null, "/workspace",
            Instant.now(), Instant.now());

    @Tool(name = "echo", description = "回显")
    static class EchoTool implements AgentTool {
        private final AtomicInteger executed;

        EchoTool(AtomicInteger executed) {
            this.executed = executed;
        }

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
            return ToolResult.success("echo ok");
        }
    }

    @Tool(name = "danger_op", description = "危险操作")
    static class DangerTool implements AgentTool {
        private final AtomicInteger executed;

        DangerTool(AtomicInteger executed) {
            this.executed = executed;
        }

        @Override
        public String name() {
            return "danger_op";
        }

        @Override
        public ToolSchema getSchema() {
            return ToolSchema.builder().name(name()).description("").build();
        }

        @Override
        public ToolResult execute(ToolCall call, ToolContext context) {
            executed.incrementAndGet();
            return ToolResult.success("danger executed");
        }
    }

    /** 受限 AgentProvider：main 全量；limited 只允许 echo。 */
    private AgentProvider restrictedProvider() {
        com.example.dsh.agent.AgentProperties props = new com.example.dsh.agent.AgentProperties();
        Map<String, com.example.dsh.agent.AgentProperties.AgentSpec> map = new LinkedHashMap<>();
        com.example.dsh.agent.AgentProperties.AgentSpec main = new com.example.dsh.agent.AgentProperties.AgentSpec();
        main.setModel("deepseek-chat");
        map.put("main", main);
        com.example.dsh.agent.AgentProperties.AgentSpec limited = new com.example.dsh.agent.AgentProperties.AgentSpec();
        limited.setModel("deepseek-chat");
        limited.setEnabledTools(List.of("echo"));
        map.put("limited", limited);
        props.setAgents(map);
        return new AgentProvider(props, "main");
    }

    @Test
    void restrictedToolIsRejectedEvenIfModelCallsIt() {
        AtomicInteger echoExecuted = new AtomicInteger();
        AtomicInteger dangerExecuted = new AtomicInteger();
        // 脚本网关：先调用受限工具 danger_op，再返回最终文本
        LlmGateway gateway = new LlmGateway() {
            private final AtomicInteger n = new AtomicInteger();

            @Override
            public ChatResponse call(List<Message> messages, ChatOptions options) {
                int i = n.getAndIncrement();
                if (i == 0) {
                    return new ChatResponse(List.of(new Generation(AssistantMessage.builder()
                            .toolCalls(List.of(new AssistantMessage.ToolCall("c1", "function", "danger_op", "{}")))
                            .build())));
                }
                return new ChatResponse(List.of(new Generation(new AssistantMessage("受限处理完成"))));
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

        List<SessionMessage> persisted = new ArrayList<>();
        SessionService sessionService = mock(SessionService.class);
        when(sessionService.getSession(sessionId)).thenReturn(session);
        when(sessionService.listMessages(sessionId)).thenReturn(List.copyOf(persisted));
        when(sessionService.append(any(), any(), any(), any(), any(), any())).thenAnswer(inv -> {
            SessionMessage m = new SessionMessage("msg_" + persisted.size(), sessionId,
                    inv.getArgument(1), inv.getArgument(2), inv.getArgument(3),
                    inv.getArgument(4), inv.getArgument(5), persisted.size() + 1L, Instant.now());
            persisted.add(m);
            return m;
        });

        ApplicationContext ctx = mock(ApplicationContext.class);
        when(ctx.getBeansOfType(AgentTool.class)).thenReturn(Map.of(
                "echoTool", new EchoTool(echoExecuted),
                "dangerTool", new DangerTool(dangerExecuted)));
        ToolRegistry registry = new ToolRegistry(ctx);
        SessionEventBus bus = new SessionEventBus();
        ToolExecutionPipeline pipeline = new ToolExecutionPipeline(registry, List.of(), List.of(),
                new ToolEventPublisher(bus, new JsonUtils()), new JsonUtils());
        CompactionService compaction = new CompactionService(new CompactionProperties(false, 8000, 40, 2000));
        AgentLoopService loop = new AgentLoopService(gateway, sessionService, registry, pipeline,
                new SystemPromptService(List.of()), restrictedProvider(),
                null, // 默认作用域（未用注册表）
                null, // 无凭据服务（不解析 agent credentialRef）
                null, // 无转存服务
                new MessageProjector(new JsonUtils()), bus, new JsonUtils(),
                new AgentLoopProperties(10, 0.7, 200, 4), compaction,
                null, // 无压缩边界存储
                new com.example.dsh.sandbox.SandboxPolicyService("workspace-write"),
                new ModelRetryPolicy(1, 1, 10), null, null);

        AgentRunResult result = loop.run(AgentRunRequest.builder()
                .sessionId(sessionId)
                .agentId("limited")
                .userMessage("调用危险操作")
                .build());

        assertEquals("受限处理完成", result.content());
        assertEquals(0, dangerExecuted.get(), "受限工具不应被执行");
        assertEquals(0, echoExecuted.get());
        // 受限调用的拒绝结果已持久化
        SessionMessage toolMsg = persisted.stream()
                .filter(m -> m.role() == MessageRole.TOOL).findFirst().orElse(null);
        assertTrue(toolMsg != null && toolMsg.content().contains("不可用"), "应持久化受限拒绝结果");
    }
}
