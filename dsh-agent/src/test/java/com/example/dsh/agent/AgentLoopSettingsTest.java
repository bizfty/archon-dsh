package com.example.dsh.agent;

import com.example.dsh.compaction.CompactionProperties;
import com.example.dsh.compaction.CompactionService;
import com.example.dsh.core.event.SessionEventBus;
import com.example.dsh.core.model.Session;
import com.example.dsh.core.model.SessionId;
import com.example.dsh.core.prompt.SystemPromptService;
import com.example.dsh.llm.LlmGateway;
import com.example.dsh.sandbox.SandboxPolicyService;
import com.example.dsh.session.SessionService;
import com.example.dsh.settings.SettingsService;
import com.example.dsh.storage.InMemoryStorageBackend;
import com.example.dsh.storage.StorageService;
import com.example.dsh.tool.ToolExecutionPipeline;
import com.example.dsh.tool.ToolEventPublisher;
import com.example.dsh.tool.ToolRegistry;
import com.example.dsh.util.JsonUtils;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * settings 消费测试：agent.temperature 覆盖经 SettingsService 生效于模型调用选项。
 */
class AgentLoopSettingsTest {

    private final SessionId sessionId = SessionId.of("sess_set");
    private final Session session = new Session(sessionId, "settings", null, "/workspace",
            Instant.now(), Instant.now());

    @SuppressWarnings("unchecked")
    private StorageService storageService() {
        ObjectProvider<com.example.dsh.storage.StorageBackend> op = mock(ObjectProvider.class);
        when(op.orderedStream()).thenReturn(java.util.stream.Stream.of(new InMemoryStorageBackend()));
        return new StorageService(op);
    }

    @Test
    void temperatureOverrideFromSettings() {
        SettingsService settings = new SettingsService(storageService());
        settings.registerDefaults("agent", Map.of("temperature", 0.7));
        settings.set("agent", "temperature", "0.2");

        AtomicReference<Double> captured = new AtomicReference<>();
        LlmGateway gateway = new LlmGateway() {
            @Override
            public ChatResponse call(List<Message> messages, ChatOptions options) {
                captured.set(options.getTemperature());
                return new ChatResponse(List.of(new Generation(new AssistantMessage("ok"))));
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
        when(ctx.getBeansOfType(com.example.dsh.tool.AgentTool.class)).thenReturn(Map.of());
        ToolRegistry registry = new ToolRegistry(ctx);
        SessionEventBus bus = new SessionEventBus();
        ToolExecutionPipeline pipeline = new ToolExecutionPipeline(registry, List.of(), List.of(),
                new ToolEventPublisher(bus, new JsonUtils()), new JsonUtils());
        CompactionService compaction = new CompactionService(new CompactionProperties(false, 8000, 40, 2000));
        AgentLoopService loop = new AgentLoopService(gateway, sessionService, registry, pipeline,
                new SystemPromptService(List.of()),
                new AgentProvider("main", "Archon", "deepseek", "", "", "/workspace"),
                null, // 默认作用域（未用注册表）
                null, // 无凭据服务（不解析 agent credentialRef）
                null, // 无转存服务
                new MessageProjector(new JsonUtils()), bus, new JsonUtils(),
                new AgentLoopProperties(10, 0.7, 200, 4),
                compaction, null, // 无压缩边界存储
                new SandboxPolicyService("workspace-write"),
                new ModelRetryPolicy(1, 1, 10), settings, null);

        loop.run(AgentRunRequest.builder().sessionId(sessionId).userMessage("hi").build());

        assertEquals(0.2, captured.get(), "settings 覆盖的温度应传入模型选项");
    }
}
