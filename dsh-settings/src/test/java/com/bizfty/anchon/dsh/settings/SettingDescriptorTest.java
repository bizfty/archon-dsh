package com.bizfty.anchon.dsh.settings;

import com.bizfty.anchon.dsh.storage.InMemoryStorageBackend;
import com.bizfty.anchon.dsh.storage.StorageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 设置描述符测试：注册/describe 保序、命名空间枚举、与默认值/覆盖的合并视图；
 * P2 扩展：type 常量、group/list/withVisible 树构造、JSON 序列化省略 null 与递归往返。
 */
class SettingDescriptorTest {

    @SuppressWarnings("unchecked")
    private SettingsService service() {
        ObjectProvider<com.bizfty.anchon.dsh.storage.StorageBackend> op = mock(ObjectProvider.class);
        when(op.orderedStream()).thenReturn(Stream.of(new InMemoryStorageBackend()));
        return new SettingsService(new StorageService(op), JsonMapper.builder().build());
    }

    private SettingDescriptor desc(String key, String type) {
        return SettingDescriptor.leaf(key, type, key + "-label", key + "-desc", null,
                type.equals("enum") ? List.of("a", "b") : null,
                type.equals("number") ? 0.0 : null,
                type.equals("number") ? 2.0 : null,
                0.1);
    }

    // ===== P2：type 常量 =====

    @Test
    void typeConstantsMatchWireStrings() {
        assertEquals("string", SettingDescriptor.Types.STRING);
        assertEquals("number", SettingDescriptor.Types.NUMBER);
        assertEquals("integer", SettingDescriptor.Types.INTEGER);
        assertEquals("boolean", SettingDescriptor.Types.BOOLEAN);
        assertEquals("enum", SettingDescriptor.Types.ENUM);
        assertEquals("object", SettingDescriptor.Types.OBJECT);
        assertEquals("array", SettingDescriptor.Types.ARRAY);
    }

    // ===== P2：树构造 =====

    @Test
    void groupListWithVisibleWhenBuildsTree() {
        SettingDescriptor profile = SettingDescriptor.group("profile", "用户档案", "个人信息",
                SettingDescriptor.leaf("name", SettingDescriptor.Types.STRING, "昵称", "显示名", null),
                SettingDescriptor.number("age", SettingDescriptor.Types.INTEGER, "年龄", null, 18, 0.0, 150.0, 1.0),
                SettingDescriptor.choice("gender", "性别", null, "unknown", List.of("male", "female", "unknown")));
        assertEquals(SettingDescriptor.Types.OBJECT, profile.type());
        assertEquals("profile", profile.key());
        assertEquals(3, profile.children().size());
        assertEquals("name", profile.children().get(0).key());
        assertEquals("age", profile.children().get(1).key());
        assertEquals(150.0, profile.children().get(1).max());
        assertEquals(List.of("male", "female", "unknown"), profile.children().get(2).options());

        SettingDescriptor prompts = SettingDescriptor.list("prompts", "提示集", "提示列表",
                SettingDescriptor.leaf("prompt", SettingDescriptor.Types.STRING, "提示", null, ""));
        assertEquals(SettingDescriptor.Types.ARRAY, prompts.type());
        assertSame(SettingDescriptor.Types.STRING, prompts.items().type());

        SettingDescriptor gated = SettingDescriptor.withVisible(prompts, "enable-custom", true);
        assertTrue(gated.visibleWhen() != null);
        assertEquals("enable-custom", gated.visibleWhen().key());
        assertEquals(true, gated.visibleWhen().equals());
        assertNull(prompts.visibleWhen(), "withVisible 不改原实例");
    }

    // ===== P2：JSON 序列化省略 null =====

    @Test
    void leafSerializationOmitsNullOptionalFields() throws Exception {
        JsonMapper mapper = JsonMapper.builder().build();
        SettingDescriptor leaf = SettingDescriptor.leaf("max-steps", "integer", "最大步数", null, 100);
        String json = mapper.writeValueAsString(leaf);
        assertTrue(json.contains("\"key\":\"max-steps\""), json);
        assertTrue(json.contains("\"type\":\"integer\""), json);
        assertTrue(json.contains("\"label\":\"最大步数\""), json);
        assertTrue(json.contains("\"defaultValue\":100"), json);
        assertFalse(json.contains("description"), json);
        assertFalse(json.contains("children"), json);
        assertFalse(json.contains("items"), json);
        assertFalse(json.contains("visibleWhen"), json);
        assertFalse(json.contains("options"), json);
    }

    // ===== P2：递归 children/items/visibleWhen JSON 往返 =====

    @Test
    void nestedTreeSerializesAndRoundTrips() throws Exception {
        JsonMapper mapper = JsonMapper.builder().build();
        SettingDescriptor tree = SettingDescriptor.group("toolbox", "工具箱", null,
                SettingDescriptor.list("proxies", "代理列表", null,
                        SettingDescriptor.group("", null, null,
                                SettingDescriptor.leaf("host", "string", "主机", null, ""),
                                SettingDescriptor.leaf("port", "integer", "端口", null, 8080))),
                SettingDescriptor.withVisible(
                        SettingDescriptor.leaf("max", "integer", "上限", null, 5), "enabled", true));

        String json = mapper.writeValueAsString(tree);
        assertTrue(json.contains("\"type\":\"object\""), json);
        assertTrue(json.contains("\"type\":\"array\""), json);
        assertTrue(json.contains("\"visibleWhen\""), json);

        SettingDescriptor back = mapper.readValue(json, SettingDescriptor.class);
        assertEquals(tree, back, "整树递归往返相等（含 children/items/visibleWhen）");
    }

    // ===== 存量行为（不回退）=====

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
