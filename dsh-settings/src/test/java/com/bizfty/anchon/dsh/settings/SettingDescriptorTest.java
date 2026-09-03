package com.bizfty.anchon.dsh.settings;

import com.bizfty.anchon.dsh.storage.InMemoryStorageBackend;
import com.bizfty.anchon.dsh.storage.StorageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 设置描述符测试：注册/describe 保序、命名空间枚举、与默认值/覆盖的合并视图。
 */
class SettingDescriptorTest {

    @SuppressWarnings("unchecked")
    private SettingsService service() {
        ObjectProvider<com.bizfty.anchon.dsh.storage.StorageBackend> op = mock(ObjectProvider.class);
        when(op.orderedStream()).thenReturn(Stream.of(new InMemoryStorageBackend()));
        return new SettingsService(new StorageService(op));
    }

    private SettingDescriptor desc(String key, String type) {
        return new SettingDescriptor(key, type, key + "-label", key + "-desc", null,
                type.equals("enum") ? List.of("a", "b") : null,
                type.equals("number") ? 0.0 : null,
                type.equals("number") ? 2.0 : null,
                0.1);
    }

    @Test
    void describeKeepsRegistrationOrderAndNamespacesSorted() {
        SettingsService service = service();
        service.registerDescriptor("agent", desc("temperature", "number"));
        service.registerDescriptor("agent", desc("max-steps", "integer"));
        service.registerDescriptor("agent", desc("mode", "enum"));
        service.registerDescriptor("ui", desc("theme", "string"));

        List<SettingDescriptor> agent = service.describe("agent");
        assertEquals(List.of("temperature", "max-steps", "mode"),
                agent.stream().map(SettingDescriptor::key).toList(), "注册顺序保序");
        assertEquals(List.of("agent", "ui"), service.describedNamespaces(), "字典序枚举");

        SettingDescriptor t = agent.get(0);
        assertEquals("number", t.type());
        assertEquals("temperature-label", t.label());
        assertEquals(0.0, t.min());
        assertEquals(2.0, t.max());
        assertEquals(0.1, t.step());
        assertEquals(List.of("a", "b"), agent.get(2).options());
    }

    @Test
    void unregisteredNamespaceDescribesEmptyAndNotEnumerated() {
        SettingsService service = service();
        service.registerDescriptor("agent", desc("temperature", "number"));

        assertTrue(service.describe("missing").isEmpty());
        assertEquals(List.of("agent"), service.describedNamespaces());
    }

    @Test
    void metaValuesMergeDefaultsAndOverrides() {
        SettingsService service = service();
        service.registerDefaults("agent", Map.of("temperature", 0.7));
        service.registerDescriptor("agent", desc("temperature", "number"));
        service.set("agent", "temperature", "0.3");

        Map<String, Object> merged = service.all("agent");
        assertEquals(0.3, merged.get("temperature"), "覆盖 > 默认");
        assertTrue(merged.containsKey("temperature"));
    }
}
