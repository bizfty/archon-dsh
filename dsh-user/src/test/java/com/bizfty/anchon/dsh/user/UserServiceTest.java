package com.bizfty.anchon.dsh.user;

import com.bizfty.anchon.dsh.credentials.CredentialService;
import com.bizfty.anchon.dsh.credentials.EnvCredentialProvider;
import com.bizfty.anchon.dsh.storage.InMemoryStorageBackend;
import com.bizfty.anchon.dsh.storage.StorageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 用户服务测试：注册/认证/重复/LLM 配置/API key 经凭据缝/持久化。
 */
class UserServiceTest {

    @SuppressWarnings("unchecked")
    private StorageService storageService() {
        ObjectProvider<com.bizfty.anchon.dsh.storage.StorageBackend> op = mock(ObjectProvider.class);
        when(op.orderedStream()).thenReturn(java.util.stream.Stream.of(new InMemoryStorageBackend()));
        return new StorageService(op);
    }

    @SuppressWarnings("unchecked")
    private CredentialService credentialService(StorageService storage) {
        ObjectProvider<com.bizfty.anchon.dsh.credentials.CredentialProvider> op = mock(ObjectProvider.class);
        when(op.orderedStream()).thenReturn(java.util.stream.Stream.of(new EnvCredentialProvider()));
        return new CredentialService(op, storage);
    }

    @Test
    void registerAndAuthenticate() {
        StorageService storage = storageService();
        UserService service = new UserService(storage, credentialService(storage));
        service.register("Alice", "pass1234", "deepseek", "deepseek-chat");

        assertTrue(service.authenticate("alice", "pass1234"), "用户名应大小写不敏感");
        assertFalse(service.authenticate("alice", "wrong"), "错误密码应失败");
        assertTrue(service.authenticate("Alice", "pass1234"));
    }

    @Test
    void duplicateUsernameRejected() {
        StorageService storage = storageService();
        UserService service = new UserService(storage, credentialService(storage));
        service.register("bob", "pass1234", null, null);
        assertThrows(IllegalArgumentException.class,
                () -> service.register("BOB", "pass5678", null, null), "重复用户名应拒绝");
    }

    @Test
    void shortPasswordRejected() {
        StorageService storage = storageService();
        UserService service = new UserService(storage, credentialService(storage));
        assertThrows(IllegalArgumentException.class, () -> service.register("carol", "abc", null, null));
    }

    @Test
    void updateLlmConfigAndApiKey() {
        StorageService storage = storageService();
        UserService service = new UserService(storage, credentialService(storage));
        UserProfile profile = service.register("dave", "pass1234", "deepseek", "m1");

        UserProfile updated = service.updateLlmConfig(profile.id(), "openai", "gpt-x", "sk-user-key");
        assertEquals("openai", updated.llmProvider());
        assertEquals("gpt-x", updated.llmModel());
        assertEquals("sk-user-key", service.getLlmApiKey(profile.id()).orElse(""), "API key 应经凭据缝可读");
    }

    @Test
    void persistsAcrossInstances(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) {
        StorageService first = new StorageService(storageProvider(
                new com.bizfty.anchon.dsh.storage.JsonFileStorageBackend(dir.toString())));
        UserService service = new UserService(first, credentialService(first));
        UserProfile profile = service.register("eve", "pass1234", "deepseek", "deepseek-chat");
        service.updateLlmConfig(profile.id(), null, null, "sk-persisted");

        // 模拟重启：新实例读同一目录
        StorageService second = new StorageService(storageProvider(
                new com.bizfty.anchon.dsh.storage.JsonFileStorageBackend(dir.toString())));
        UserService reloaded = new UserService(second, credentialService(second));
        assertTrue(reloaded.authenticate("eve", "pass1234"), "用户应跨实例存活");
        assertEquals("deepseek-chat", reloaded.findByUsername("eve").get().llmModel());
        assertEquals("sk-persisted", reloaded.getLlmApiKey(profile.id()).orElse(""), "API key 应跨实例存活");
    }

    @SuppressWarnings("unchecked")
    private static org.springframework.beans.factory.ObjectProvider<com.bizfty.anchon.dsh.storage.StorageBackend> storageProvider(
            com.bizfty.anchon.dsh.storage.StorageBackend backend) {
        org.springframework.beans.factory.ObjectProvider<com.bizfty.anchon.dsh.storage.StorageBackend> op =
                mock(org.springframework.beans.factory.ObjectProvider.class);
        when(op.orderedStream()).thenReturn(java.util.stream.Stream.of(backend));
        return op;
    }
}
