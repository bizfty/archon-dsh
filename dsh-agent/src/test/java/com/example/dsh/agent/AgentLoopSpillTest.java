package com.example.dsh.agent;

import com.example.dsh.compaction.CompactionBoundaryStore;
import com.example.dsh.compaction.CompactionProperties;
import com.example.dsh.compaction.CompactionService;
import com.example.dsh.compaction.SpillProperties;
import com.example.dsh.compaction.SpillService;
import com.example.dsh.core.model.MessageRole;
import com.example.dsh.core.model.Session;
import com.example.dsh.core.model.SessionId;
import com.example.dsh.core.model.SessionMessage;
import com.example.dsh.core.prompt.SystemPromptService;
import com.example.dsh.llm.LlmGateway;
import com.example.dsh.session.SessionService;
import com.example.dsh.storage.InMemoryStorageBackend;
import com.example.dsh.storage.StorageService;
import com.example.dsh.tool.AgentTool;
import com.example.dsh.tool.ToolExecutionPipeline;
import com.example.dsh.tool.ToolEventPublisher;
import com.example.dsh.tool.ToolRegistry;
import com.example.dsh.util.JsonUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.context.ApplicationContext;
import reactor.core.publisher.Flux;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 工具输出转存集成测试：超大工具结果经 agent-loop 转存文件，
 * 模型收到的 ToolResponseMessage 为 预览+定位符（非全文）。
 */
class AgentLoopSpillTest {

    private final SessionId sessionId = SessionId.of("sess_spill");
    private final Session session = new Session(sessionId, "转存", null, "/workspace",
            Instant.now(), Instant.now());

    @SuppressWarnings("unchecked")
    private static org.springframework.beans.factory.ObjectProvider<com.example.dsh.storage.StorageBackend> storageProvider() {
        org.springframework.beans.factory.ObjectProvider<com.example.dsh.storage.StorageBackend> sp =
                mock(org.springframework.beans.factory.ObjectProvider.class);
        when(sp.orderedStream()).thenReturn(Stream.of(new InMemoryStorageBackend()));
        return sp;
    }

    @Test
    void hugeToolResultSpilledInLoop(@TempDir Path spillDir) throws Exception {
        // 脚本网关：第 1 次 = 工具调用（bash，参数任意）；第 2 次 = 最终文本
        AtomicInteger calls = new AtomicInteger();
        List<Message> secondPrompt = new ArrayList<>();
        LlmGateway gateway = new LlmGateway() {
            @Override
            public ChatResponse call(List<Message> messages, ChatOptions options) {
                int i = calls.getAndIncrement();
                if (i == 0) {
                    return new ChatResponse(List.of(new Generation(AssistantMessage.builder()
                            .toolCalls(List.of(new AssistantMessage.ToolCall(
                                    "call_1", "function", "bash", "{\"command\":\"echo big\"}")))
                            .build())));
                }
                secondPrompt.addAll(messages);
                return new ChatResponse(List.of(new Generation(new AssistantMessage("完成"))));
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

        // bash 工具返回 5000 字符结果（超过 spill 阈值 100）
        ApplicationContext ctx = mock(ApplicationContext.class);
        when(ctx.getBeansOfType(AgentTool.class)).thenReturn(Map.of(
                "bashTool", new BigResultTool("x".repeat(5000))));
        ToolRegistry registry = new ToolRegistry(ctx);
        var bus = new com.example.dsh.core.event.SessionEventBus();
        ToolExecutionPipeline pipeline = new ToolExecutionPipeline(registry, List.of(), List.of(),
                new ToolEventPublisher(bus, new JsonUtils()), new JsonUtils());

        List<SessionMessage> persisted = new ArrayList<>();
        SessionService sessionService = mock(SessionService.class);
        when(sessionService.getSession(sessionId)).thenReturn(session);
        when(sessionService.listMessages(sessionId)).thenAnswer(inv -> List.copyOf(persisted));
        when(sessionService.append(any(), any(), any(), any(), any(), any())).thenAnswer(inv -> {
            SessionMessage m = new SessionMessage("m" + persisted.size(), sessionId,
                    inv.getArgument(1), inv.getArgument(2), inv.getArgument(3),
                    inv.getArgument(4), inv.getArgument(5), persisted.size() + 1L, Instant.now());
            persisted.add(m);
            return m;
        });

        SpillService spill = new SpillService(new SpillProperties(true, 300, spillDir.toString()));
        CompactionService compaction = new CompactionService(new CompactionProperties(false, 8000, 40, 2000));
        AgentLoopService loop = new AgentLoopService(gateway, sessionService, registry, pipeline,
                new SystemPromptService(List.of()),
                new AgentProvider("main", "Archon", "deepseek", "", "", "/workspace"),
                null, null, spill, // 默认作用域、无凭据服务、启用转存
                new MessageProjector(new JsonUtils()), bus, new JsonUtils(),
                new AgentLoopProperties(10, 0.7, 200, 4), compaction,
                new CompactionBoundaryStore(new StorageService(storageProvider())),
                new com.example.dsh.sandbox.SandboxPolicyService("workspace-write"),
                new ModelRetryPolicy(1, 1, 10), null, null);

        AgentRunResult result = loop.run(AgentRunRequest.builder()
                .sessionId(sessionId)
                .userMessage("运行 bash")
                .build());
        assertEquals("完成", result.content());

        // 模型第 2 次收到的提示里，TOOL 消息是预览+定位符，不是全文
        String toolText = secondPrompt.stream()
                .filter(m -> m instanceof ToolResponseMessage)
                .map(m -> ((ToolResponseMessage) m).getResponses().stream()
                        .map(r -> r.responseData()).reduce("", (a, b) -> a + b))
                .reduce("", (a, b) -> a + b);
        assertTrue(toolText.contains("完整输出已转存"), "模型应看到转存定位符");
        assertTrue(toolText.contains("read_file"), "定位符含取回指引");
        assertTrue(toolText.length() < 500, "模型上下文中的工具结果应远小于 5000 字符");

        // 日志中的 TOOL 行也是预览（非全文）
        SessionMessage toolRow = persisted.stream()
                .filter(m -> m.role() == MessageRole.TOOL).findFirst().orElseThrow();
        assertTrue(toolRow.content().contains("完整输出已转存"));
        assertTrue(toolRow.content().length() < 500);

        // 转存文件存在且为全文
        try (var stream = Files.walk(spillDir)) {
            Path file = stream.filter(Files::isRegularFile).findFirst().orElseThrow();
            String full = Files.readString(file);
            assertTrue(full.contains("x".repeat(100)), "转存文件应含完整输出");
        }
    }

    /** 返回大结果文本的测试工具。 */
    static final class BigResultTool implements AgentTool {
        private final String bigText;

        BigResultTool(String bigText) {
            this.bigText = bigText;
        }

        @Override
        public String name() {
            return "bash";
        }

        @Override
        public com.example.dsh.tool.ToolSchema getSchema() {
            return com.example.dsh.tool.ToolSchema.builder()
                    .name(name())
                    .description("大输出")
                    .addParameter("command", "string", "命令")
                    .required("command")
                    .build();
        }

        @Override
        public com.example.dsh.tool.ToolResult execute(com.example.dsh.tool.ToolCall call,
                                                       com.example.dsh.tool.ToolContext context) {
            return com.example.dsh.tool.ToolResult.success(bigText);
        }
    }
}
