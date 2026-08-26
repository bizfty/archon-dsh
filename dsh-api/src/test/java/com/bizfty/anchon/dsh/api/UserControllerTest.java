package com.bizfty.anchon.dsh.api;

import com.bizfty.anchon.dsh.credentials.CredentialService;
import com.bizfty.anchon.dsh.credentials.EnvCredentialProvider;
import com.bizfty.anchon.dsh.storage.InMemoryStorageBackend;
import com.bizfty.anchon.dsh.storage.StorageService;
import com.bizfty.anchon.dsh.user.AuthService;
import com.bizfty.anchon.dsh.user.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 用户端点测试：注册→登录→me→更新 LLM 配置 全流程。
 */
class UserControllerTest {

    private static class Fixture {
        UserController controller;
        AuthService auth;
        UserService users;
        String token;

        Fixture() {
            @SuppressWarnings("unchecked")
            ObjectProvider<com.bizfty.anchon.dsh.storage.StorageBackend> sp = mock(ObjectProvider.class);
            when(sp.orderedStream()).thenReturn(java.util.stream.Stream.of(new InMemoryStorageBackend()));
            StorageService storage = new StorageService(sp);
            @SuppressWarnings("unchecked")
            ObjectProvider<com.bizfty.anchon.dsh.credentials.CredentialProvider> cp = mock(ObjectProvider.class);
            when(cp.orderedStream()).thenReturn(java.util.stream.Stream.of(new EnvCredentialProvider()));
            users = new UserService(storage, new CredentialService(cp));
            auth = new AuthService(users, 60);
            controller = new UserController(users, auth);
        }

        void registerAndLogin() {
            controller.register(new UserController.RegisterRequest("user1", "pass1234", "deepseek", "deepseek-chat"));
            var login = controller.login(new UserController.LoginRequest("user1", "pass1234"));
            token = String.valueOf(((Map<?, ?>) login.getBody()).get("token"));
        }
    }

    @Test
    void registerLoginMeFlow() {
        Fixture fixture = new Fixture();
        fixture.registerAndLogin();
        assertTrue(fixture.token.startsWith("tok_"));

        var me = fixture.controller.me(fixture.token);
        assertTrue(me.getStatusCode().is2xxSuccessful());
        com.bizfty.anchon.dsh.user.UserProfile.UserView view = (com.bizfty.anchon.dsh.user.UserProfile.UserView) me.getBody();
        assertEquals("user1", view.username());
        assertEquals("deepseek", view.llmProvider());
    }

    @Test
    void unauthMeReturns401() {
        Fixture fixture = new Fixture();
        var me = fixture.controller.me(null);
        assertTrue(me.getStatusCode().value() == 401);
    }

    @Test
    void updateLlmConfigReflects() {
        Fixture fixture = new Fixture();
        fixture.registerAndLogin();

        var updated = fixture.controller.updateLlm(fixture.token,
                new UserController.UpdateLlmRequest("openai", "gpt-4o", "sk-user-1"));
        assertTrue(updated.getStatusCode().is2xxSuccessful());
        com.bizfty.anchon.dsh.user.UserProfile.UserView view =
                (com.bizfty.anchon.dsh.user.UserProfile.UserView) updated.getBody();
        assertEquals("openai", view.llmProvider());
        assertEquals("gpt-4o", view.llmModel());
        // API key 经凭据缝可读
        assertEquals("sk-user-1", fixture.users.getLlmApiKey(
                String.valueOf(fixture.users.findByUsername("user1").get().id())).orElse(""));
    }

    @Test
    void badLoginReturns401() {
        Fixture fixture = new Fixture();
        fixture.registerAndLogin();
        var bad = fixture.controller.login(new UserController.LoginRequest("user1", "wrong"));
        assertTrue(bad.getStatusCode().value() == 401);
    }

    @Test
    void updateLlmJsonMappingBindsProviderAndModel() throws Exception {
        // 防止字段名与 JSON 键不一致导致静默 null 的回归（曾发生的 bug）
        tools.jackson.databind.json.JsonMapper mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        var request = mapper.readValue(
                "{\"llmProvider\":\"openai\",\"llmModel\":\"gpt-4o\",\"apiKey\":\"sk-user-1\"}",
                UserController.UpdateLlmRequest.class);
        assertEquals("openai", request.llmProvider());
        assertEquals("gpt-4o", request.llmModel());
        assertEquals("sk-user-1", request.apiKey());
    }
}
