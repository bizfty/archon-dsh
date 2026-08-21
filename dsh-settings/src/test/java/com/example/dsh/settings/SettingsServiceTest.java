package com.example.dsh.settings;

import com.example.dsh.storage.InMemoryStorageBackend;
import com.example.dsh.storage.StorageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 设置服务测试：分层解析、类型化、持久化覆盖。
 */
class SettingsServiceTest {

    @SuppressWarnings("unchecked")
    private SettingsService serviceWithDefaults() {
        ObjectProvider<com.example.dsh.storage.StorageBackend> op = mock(ObjectProvider.class);
        when(op.orderedStream()).thenReturn(java.util.stream.Stream.of(new InMemoryStorageBackend()));
        SettingsService service = new SettingsService(new StorageService(op));
        service.registerDefaults("agent", Map.of(
                "temperature", 0.7,
                "max-steps", 25,
                "label", "default-label"));
        return service;
    }

    @Test
    void fallsBackToSchemaDefaults() {
        SettingsService service = serviceWithDefaults();
        assertEquals(0.7, service.get("agent", "temperature"));
        assertEquals(25, service.getInt("agent", "max-steps", 0));
        assertEquals("default-label", service.getString("agent", "label", ""));
        assertNull(service.get("agent", "missing"));
    }

    @Test
    void overridesPersistAndWin(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) {
        SettingsService service = serviceWithBackend(new com.example.dsh.storage.JsonFileStorageBackend(dir.toString()));
        service.set("agent", "temperature", "0.3");
        assertEquals(0.3, service.get("agent", "temperature"));
        // 新实例（同 JSON 文件目录）仍读到覆盖 → 持久化
        SettingsService fresh = serviceWithBackend(new com.example.dsh.storage.JsonFileStorageBackend(dir.toString()));
        fresh.registerDefaults("agent", Map.of("temperature", 0.7));
        assertEquals(0.3, fresh.get("agent", "temperature"), "覆盖应持久化（跨实例）");
    }

    @SuppressWarnings("unchecked")
    private SettingsService serviceWithBackend(com.example.dsh.storage.StorageBackend backend) {
        ObjectProvider<com.example.dsh.storage.StorageBackend> op = mock(ObjectProvider.class);
        when(op.orderedStream()).thenReturn(java.util.stream.Stream.of(backend));
        return new SettingsService(new com.example.dsh.storage.StorageService(op));
    }

    @Test
    void allMergesDefaultsAndOverrides() {
        SettingsService service = serviceWithDefaults();
        service.set("agent", "temperature", "0.2");
        Map<String, Object> all = service.all("agent");
        assertEquals(0.2, all.get("temperature"));
        assertEquals(25, all.get("max-steps"));
    }
}
