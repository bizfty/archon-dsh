package com.bizfty.anchon.dsh.api.openai;

import com.bizfty.anchon.dsh.agent.AgentLoopService;
import com.bizfty.anchon.dsh.agent.AgentRunRequest;
import com.bizfty.anchon.dsh.agent.AgentRunResult;
import com.bizfty.anchon.dsh.credentials.CredentialService;
import com.bizfty.anchon.dsh.credentials.EnvCredentialProvider;
import com.bizfty.anchon.dsh.core.model.Session;
import com.bizfty.anchon.dsh.core.model.SessionId;
import com.bizfty.anchon.dsh.session.SessionService;
import com.bizfty.anchon.dsh.storage.InMemoryStorageBackend;
import com.bizfty.anchon.dsh.storage.StorageService;
import com.bizfty.anchon.dsh.user.AuthService;
import com.bizfty.anchon.dsh.user.UserService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 用户 LLM API key 路由测试：带 token 请求 → AgentRunRequest.apiKeyOverride 为用户 key；
 * 无 token / 未配置 key → 不注入（用全局 key）。
 */
class ChatCompletionControllerKeyRoutingTest {

    private static class Fixture {
        UserControllerHolder holder;
        UserService users;
        AuthService auth;
        AgentLoopService agentLoop;
        String token;

        Fixture() {
            @SuppressWarnings("unchecked")
            ObjectProvider<com.bizfty.anchon.dsh.storage.StorageBackend> sp = mock(ObjectProvider.class);
            when(sp.orderedStream()).thenReturn(Stream.of(new InMemoryStorageBackend()));
            StorageService storage = new StorageService(sp);
            @SuppressWarnings("unchecked")
            ObjectProvider<com.bizfty.anchon.dsh.credentials.CredentialProvider> cp = mock(ObjectProvider.class);
            when(cp.orderedStream()).thenReturn(Stream.of(new EnvCredentialProvider()));
            users = new UserService(storage, new CredentialService(cp));
            auth = new AuthService(users, 60);
            agentLoop = mock(AgentLoopService.class);
            when(agentLoop.run(any(AgentRunRequest.class))).thenReturn(
                    AgentRunResult.text("ok", SessionId.of("s_test"), 1, 0));
            SessionService sessions = mock(SessionService.class);
            when(sessions.createSession(any(), any(), any())).thenReturn(
                    new Session(SessionId.of("s_test"), "t", "deepseek-chat", "/tmp",
                            java.time.Instant.now(), java.time.Instant.now()));
            var controller = new ChatCompletionController(sessions, agentLoop, auth, users);
            holder = new UserControllerHolder(controller);
        }

        void registerAndLogin(String apiKey) {
            var uc = new com.bizfty.anchon.dsh.api.UserController(users, auth);
            uc.register(new com.bizfty.anchon.dsh.api.UserController.RegisterRequest("u1", "pass1234", "deepseek", "deepseek-chat"));
            var login = uc.login(new com.bizfty.anchon.dsh.api.UserController.LoginRequest("u1", "pass1234"));
            token = String.valueOf(((Map<?, ?>) login.getBody()).get("token"));
            var userId = users.findByUsername("u1").orElseThrow().id();
            if (apiKey != null) {
                users.setLlmApiKey(userId, apiKey);
            }
        }

        static class UserControllerHolder {
            final ChatCompletionController controller;

            UserControllerHolder(ChatCompletionController controller) {
                this.controller = controller;
            }
        }
    }

    private AgentRunRequest captured(Fixture fixture, MockHttpServletRequest req) {
        ArgumentCaptor<AgentRunRequest> captor = ArgumentCaptor.forClass(AgentRunRequest.class);
        fixture.holder.controller.chatCompletions(new ChatCompletionRequest(
                "deepseek-chat", List.of(new ChatCompletionRequest.ChatMessage("user", "hi")), null, null), req);
        verify(fixture.agentLoop).run(captor.capture());
        return captor.getValue();
    }

    @Test
    void tokenWithUserKeyRoutesApiKeyOverride() {
        Fixture fixture = new Fixture();
        fixture.registerAndLogin("sk-user-secret");
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Auth-Token", fixture.token);
        AgentRunRequest run = captured(fixture, req);
        assertEquals("sk-user-secret", run.apiKeyOverride());
    }

    @Test
    void noTokenLeavesApiKeyOverrideNull() {
        Fixture fixture = new Fixture();
        fixture.registerAndLogin("sk-user-secret");
        AgentRunRequest run = captured(fixture, new MockHttpServletRequest());
        assertNull(run.apiKeyOverride());
    }

    @Test
    void userWithoutConfiguredKeyLeavesOverrideNull() {
        Fixture fixture = new Fixture();
        fixture.registerAndLogin(null);
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Auth-Token", fixture.token);
        AgentRunRequest run = captured(fixture, req);
        assertNull(run.apiKeyOverride());
    }
}
