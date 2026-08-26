package com.bizfty.anchon.dsh.agent;

import com.bizfty.anchon.dsh.compaction.CompactionBoundaryStore;
import com.bizfty.anchon.dsh.compaction.CompactionProperties;
import com.bizfty.anchon.dsh.compaction.CompactionService;
import com.bizfty.anchon.dsh.core.model.Session;
import com.bizfty.anchon.dsh.core.model.SessionId;
import com.bizfty.anchon.dsh.core.prompt.SystemPromptService;
import com.bizfty.anchon.dsh.credentials.CredentialRef;
import com.bizfty.anchon.dsh.credentials.CredentialService;
import com.bizfty.anchon.dsh.credentials.EnvCredentialProvider;
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
import org.springframework.context.ApplicationContext;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Agent 级 LLM 凭据引用测试：agent 配置了 credentialRef 时，agent-loop 每次调用
 * 前经 CredentialService 解析该 key（请求级覆盖优先）。
 */
class AgentLoopCredentialTest {

    private final SessionId sessionId = SessionId.of("sess_cred");
    private final Session session = new Session(sessionId, "凭据", null, "/workspace",
            Instant.now(), Instant.now());

    private AgentProvider providerWithCredentialRef(String ref) {
        AgentProperties props = new AgentProperties();
        Map<String, AgentProperties.AgentSpec> map = new LinkedHashMap<>();
        AgentProperties.AgentSpec spec = new AgentProperties.AgentSpec();
        spec.setModel("deepseek-chat");
        if (ref != null) {
            spec.setCredentialRef(ref);
        }
        map.put("main", spec);
        props.setAgents(map);
        return new AgentProvider(props, "main");
    }

    @SuppressWarnings("unchecked")
    private CredentialService credentialsWith(String ref, String value) {
        org.springframework.beans.factory.ObjectProvider<com.bizfty.anchon.dsh.credentials.CredentialProvider> cp =
                mock(org.springframework.beans.factory.ObjectProvider.class);
        when(cp.orderedStream()).thenReturn(Stream.of(new EnvCredentialProvider()));
        org.springframework.beans.factory.ObjectProvider<com.bizfty.anchon.dsh.storage.StorageBackend> sp =
                mock(org.springframework.beans.factory.ObjectProvider.class);
        when(sp.orderedStream()).thenReturn(Stream.of(new InMemoryStorageBackend()));
        CredentialService service = new CredentialService(cp, new StorageService(sp));
        if (ref != null && value != null) {
            service.set(CredentialRef.parse(ref), value);
        }
        return service;
    }

    private static class ApiKeyRecordingGateway implements LlmGateway {
        final AtomicReference<String> lastKey = new AtomicReference<>();

        @Override
        public ChatResponse call(List<Message> messages, ChatOptions options) {
            return new ChatResponse(List.of(new Generation(new AssistantMessage("ok"))));
        }

        @Override
        public ChatResponse call(List<Message> messages, ChatOptions options, String apiKey) {
            lastKey.set(apiKey);
            return call(messages, options);
        }

        @Override
        public Flux<ChatResponse> stream(List<Message> messages, ChatOptions options) {
            return Flux.just(call(messages, options));
        }

        @Override
        public Flux<ChatResponse> stream(List<Message> messages, ChatOptions options, String apiKey) {
            lastKey.set(apiKey);
            return stream(messages, options);
        }

        @Override
        public String defaultModel() {
            return "deepseek-chat";
        }
    }

    private AgentLoopService loop(AgentProvider provider, CredentialService credentials,
                                  ApiKeyRecordingGateway gateway) {
        SessionService sessionService = mock(SessionService.class);
        when(sessionService.getSession(sessionId)).thenReturn(session);
        when(sessionService.listMessages(sessionId)).thenReturn(List.of());
        ApplicationContext ctx = mock(ApplicationContext.class);
        when(ctx.getBeansOfType(AgentTool.class)).thenReturn(Map.of());
        ToolRegistry registry = new ToolRegistry(ctx);
        var bus = new com.bizfty.anchon.dsh.core.event.SessionEventBus();
        ToolExecutionPipeline pipeline = new ToolExecutionPipeline(registry, List.of(), List.of(),
                new ToolEventPublisher(bus, new JsonUtils()), new JsonUtils());
        CompactionService compaction = new CompactionService(new CompactionProperties(false, 8000, 40, 2000));
        return new AgentLoopService(gateway, sessionService, registry, pipeline,
                new SystemPromptService(List.of()), provider, null, credentials, null,
                new MessageProjector(new JsonUtils()), bus, new JsonUtils(),
                new AgentLoopProperties(10, 0.7, 200, 4), compaction,
                new CompactionBoundaryStore(new StorageService(storageProvider())),
                new com.bizfty.anchon.dsh.sandbox.SandboxPolicyService("workspace-write"),
                new ModelRetryPolicy(1, 1, 10), null, null, null, null);
    }

    @SuppressWarnings("unchecked")
    private static org.springframework.beans.factory.ObjectProvider<com.bizfty.anchon.dsh.storage.StorageBackend> storageProvider() {
        org.springframework.beans.factory.ObjectProvider<com.bizfty.anchon.dsh.storage.StorageBackend> sp =
                mock(org.springframework.beans.factory.ObjectProvider.class);
        when(sp.orderedStream()).thenReturn(Stream.of(new InMemoryStorageBackend()));
        return sp;
    }

    @Test
    void agentCredentialRefResolvedPerCall() {
        ApiKeyRecordingGateway gateway = new ApiKeyRecordingGateway();
        AgentLoopService loop = loop(providerWithCredentialRef("env:AGENT_KEY"),
                credentialsWith("env:AGENT_KEY", "sk-agent-secret"), gateway);
        loop.run(AgentRunRequest.builder().sessionId(sessionId).userMessage("hi").build());
        assertEquals("sk-agent-secret", gateway.lastKey.get(),
                "agent 配置的 credentialRef 应解析为实际 key 传入模型调用");
    }

    @Test
    void requestOverrideBeatsAgentCredentialRef() {
        ApiKeyRecordingGateway gateway = new ApiKeyRecordingGateway();
        AgentLoopService loop = loop(providerWithCredentialRef("env:AGENT_KEY"),
                credentialsWith("env:AGENT_KEY", "sk-agent-secret"), gateway);
        loop.run(AgentRunRequest.builder().sessionId(sessionId).userMessage("hi")
                .apiKeyOverride("sk-request-user").build());
        assertEquals("sk-request-user", gateway.lastKey.get(),
                "请求级覆盖（如用户 profile key）应优先于 agent 凭据引用");
    }

    @Test
    void agentWithoutCredentialRefSendsNullKey() {
        ApiKeyRecordingGateway gateway = new ApiKeyRecordingGateway();
        AgentLoopService loop = loop(providerWithCredentialRef(null), credentialsWith(null, null), gateway);
        loop.run(AgentRunRequest.builder().sessionId(sessionId).userMessage("hi").build());
        assertNull(gateway.lastKey.get(), "未配置凭据的 agent 应用全局 key（apiKey=null）");
    }

    @Test
    void unresolvedCredentialRefFallsBackToGlobalKey() {
        ApiKeyRecordingGateway gateway = new ApiKeyRecordingGateway();
        // 引用存在但凭据未配置 → 解析 empty → 回落全局（null key）
        AgentLoopService loop = loop(providerWithCredentialRef("env:MISSING_AGENT_KEY"),
                credentialsWith("env:MISSING_AGENT_KEY", null), gateway);
        loop.run(AgentRunRequest.builder().sessionId(sessionId).userMessage("hi").build());
        assertNull(gateway.lastKey.get());
    }
}
