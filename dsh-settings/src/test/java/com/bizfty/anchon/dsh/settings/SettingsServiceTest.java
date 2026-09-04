package com.bizfty.anchon.dsh.settings;

import com.bizfty.anchon.dsh.storage.InMemoryStorageBackend;
import com.bizfty.anchon.dsh.storage.StorageBackend;
import com.bizfty.anchon.dsh.storage.StorageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 设置服务测试：分层解析、类型化、持久化覆盖；
 * P2 扩展：嵌套 object/array JSON 往返、存量标量不回退、坏 JSON 兜底原文本。
 */
class SettingsServiceTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    @SuppressWarnings("unchecked")
    private SettingsService serviceWithDefaults() {
        ObjectProvider<StorageBackend> op = mock(ObjectProvider.class);
        when(op.orderedStream()).thenReturn(Stream.of(new InMemoryStorageBackend()));
        SettingsService service = new SettingsService(new StorageService(op), MAPPER);
        service.registerDefaults("agent", Map.of(
                "temperature", 0.7,
                "max-steps", 25,
                "label", "default-label"));
        return service;
    }

    @SuppressWarnings("unchecked")
    private SettingsService serviceWithBackend(com.bizfty.anchon.dsh.storage.StorageBackend backend) {
        ObjectProvider<StorageBackend> op = mock(ObjectProvider.class);
        when(op.orderedStream()).thenReturn(Stream.of(backend));
        return new SettingsService(new StorageService(op), MAPPER);
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
        SettingsService service = serviceWithBackend(new com.bizfty.anchon.dsh.storage.JsonFileStorageBackend(dir.toString()));
        service.set("agent", "temperature", "0.3");
        assertEquals(0.3, service.get("agent", "temperature"));
        // 新实例（同 JSON 文件目录）仍读到覆盖 → 持久化
        SettingsService fresh = serviceWithBackend(new com.bizfty.anchon.dsh.storage.JsonFileStorageBackend(dir.toString()));
        fresh.registerDefaults("agent", Map.of("temperature", 0.7));
        assertEquals(0.3, fresh.get("agent", "temperature"), "覆盖应持久化（跨实例）");
    }

    @Test
    void allMergesDefaultsAndOverrides() {
        SettingsService service = serviceWithDefaults();
        service.set("agent", "temperature", "0.2");
        Map<String, Object> all = service.all("agent");
        assertEquals(0.2, all.get("temperature"));
        assertEquals(25, all.get("max-steps"));
    }

    // ===== P2：嵌套 JSON 往返 =====

    @Test
    void nestedObjectRoundTripsThroughJson() {
        SettingsService service = serviceWithDefaults();
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("name", "alice");
        profile.put("age", 30);
        profile.put("tags", List.of("a", "b"));
        service.set("agent", "profile", profile);

        Object got = service.get("agent", "profile");
        assertEquals(profile, got, "嵌套 object 应 JSON 往返为同构 Map");
        assertEquals(profile, service.all("agent").get("profile"), "合并视图含嵌套结构");
    }

    @Test
    void nestedArrayOfObjectsRoundTripsThroughJson() {
        SettingsService service = serviceWithDefaults();
        List<Map<String, Object>> proxies = List.of(
                Map.of("host", "h1", "port", 8080),
                Map.of("host", "h2", "port", 9090));
        service.set("agent", "proxies", proxies);

        assertEquals(proxies, service.get("agent", "proxies"), "array 元素 object JSON 往返");
        assertEquals(proxies, service.all("agent").get("proxies"));
    }

    @Test
    void scalarOverridesKeepLegacyTextParsing() {
        SettingsService service = serviceWithDefaults();
        // 标量仍走 String.valueOf 存储 + bool/int/double/string 文本解析（存量行为）
        service.set("agent", "flag", true);
        service.set("agent", "temperature", 0.3);
        service.set("agent", "max-steps", 42);
        service.set("agent", "label", "hello world");
        assertEquals(true, service.get("agent", "flag"));
        assertEquals(0.3, service.get("agent", "temperature"));
        assertEquals(42, service.get("agent", "max-steps"));
        assertEquals("hello world", service.get("agent", "label"));
    }

    @Test
    void malformedJsonTextFallsBackToRawString() {
        ObjectProvider<StorageBackend> op = mock(ObjectProvider.class);
        when(op.orderedStream()).thenReturn(Stream.of(new InMemoryStorageBackend()));
        StorageService storage = new StorageService(op);
        SettingsService service = new SettingsService(storage, MAPPER);
        // 直接向存储写一段"花括号开头但非法 JSON"的脏文本（历史误存）
        storage.put("settings.agent", "broken", "{a=1");
        assertEquals("{a=1", service.get("agent", "broken"), "JSON 解析失败应回落原文本");
    }
}
