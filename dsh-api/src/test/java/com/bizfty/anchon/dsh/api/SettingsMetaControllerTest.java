package com.bizfty.anchon.dsh.api;

import com.bizfty.anchon.dsh.settings.SettingDescriptor;
import com.bizfty.anchon.dsh.settings.SettingsService;
import com.bizfty.anchon.dsh.storage.InMemoryStorageBackend;
import com.bizfty.anchon.dsh.storage.StorageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Settings meta 端点测试：GET /api/settings/meta → 已注册描述符命名空间的
 * 描述符 + 当前值合并视图；未注册描述符的命名空间不暴露。
 * P2 扩展：嵌套 namespace（object children/array items/visibleWhen）在 meta JSON 中出现、
 * PUT 嵌套 body → GET 返回结构化 JSON（端点 URL 不变）。
 */
class SettingsMetaControllerTest {

    @SuppressWarnings("unchecked")
    private SettingsController controller() {
        ObjectProvider<com.bizfty.anchon.dsh.storage.StorageBackend> op = mock(ObjectProvider.class);
        when(op.orderedStream()).thenReturn(Stream.of(new InMemoryStorageBackend()));
        SettingsService service = new SettingsService(new StorageService(op), JsonMapper.builder().build());
        service.registerDefaults("agent", Map.of("temperature", 0.7, "max-steps", 25));
        service.registerDescriptor("agent", SettingDescriptor.leaf(
                "temperature", "number", "温度", "模型采样温度", 0.7, null, 0.0, 2.0, 0.1));
        service.registerDescriptor("agent", SettingDescriptor.leaf(
                "max-steps", "integer", "最大步数", "单 turn 防失控兜底", 25, null, 1.0, 10000.0, 1.0));
        service.registerDefaults("hidden", Map.of("x", 1));
        return new SettingsController(service);
    }

    /** 嵌套描述符 namespace（object children / array items / visibleWhen 联动）。 */
    @SuppressWarnings("unchecked")
    private SettingsController nestedController() {
        ObjectProvider<com.bizfty.anchon.dsh.storage.StorageBackend> op = mock(ObjectProvider.class);
        when(op.orderedStream()).thenReturn(Stream.of(new InMemoryStorageBackend()));
        SettingsService service = new SettingsService(new StorageService(op), JsonMapper.builder().build());
        service.registerDescriptor("example", SettingDescriptor.group("profile", "用户档案", "个人信息",
                SettingDescriptor.leaf("name", SettingDescriptor.Types.STRING, "昵称", "显示名", ""),
                SettingDescriptor.number("age", SettingDescriptor.Types.INTEGER, "年龄", null, 18, 0.0, 150.0, 1.0),
                SettingDescriptor.choice("gender", "性别", null, "unknown", List.of("male", "female", "unknown"))));
        service.registerDescriptor("example", SettingDescriptor.list("prompts", "提示集", "自定义提示列表",
                SettingDescriptor.leaf("prompt", SettingDescriptor.Types.STRING, "提示", null, "")));
        service.registerDescriptor("example", SettingDescriptor.withVisible(
                SettingDescriptor.choice("toolset", "工具集", null, "basic",
                        List.of("basic", "advanced")),
                "mode", "advanced"));
        return new SettingsController(service);
    }

    @Test
    void metaListsOnlyDescribedNamespacesWithDescriptorAndValues() {
        SettingsController controller = controller();

        List<SettingsController.SettingsMetaDto> metas = controller.meta().getBody();
        assertEquals(1, metas.size(), "未注册描述符的 namespace 不暴露");
        SettingsController.SettingsMetaDto agent = metas.get(0);

        assertEquals("agent", agent.namespace());
        assertEquals(List.of("temperature", "max-steps"),
                agent.settings().stream().map(SettingDescriptor::key).toList(), "描述符保序");
        assertEquals(0.7, agent.values().get("temperature"), "默认值并入当前视图");
        assertEquals("温度", agent.settings().get(0).label());
        assertEquals(0.0, agent.settings().get(0).min());
    }

    @Test
    void valuesReflectOverride() {
        SettingsController controller = controller();
        controller.set("agent", "temperature", new SettingsController.SetBody(0.3));

        List<SettingsController.SettingsMetaDto> metas = controller.meta().getBody();
        assertEquals(0.3, metas.get(0).values().get("temperature"), "覆盖值反映到 meta");
        assertTrue(controller.all("agent").getStatusCode().is2xxSuccessful());
    }

    // ===== P2：嵌套 schema meta 形状 =====

    @Test
    void nestedMetaCarriesObjectChildrenArrayItemsAndVisibleWhen() {
        SettingsController controller = nestedController();

        List<SettingsController.SettingsMetaDto> metas = controller.meta().getBody();
        SettingsController.SettingsMetaDto example = metas.stream()
                .filter(m -> m.namespace().equals("example")).findFirst().orElseThrow();

        List<SettingDescriptor> settings = example.settings();
        assertEquals(3, settings.size());
        SettingDescriptor profile = settings.get(0);
        assertEquals(SettingDescriptor.Types.OBJECT, profile.type());
        assertEquals(List.of("name", "age", "gender"),
                profile.children().stream().map(SettingDescriptor::key).toList(), "children 保序");
        assertEquals(150.0, profile.children().get(1).max());

        SettingDescriptor prompts = settings.get(1);
        assertEquals(SettingDescriptor.Types.ARRAY, prompts.type());
        assertEquals("prompt", prompts.items().key());
        assertEquals(SettingDescriptor.Types.STRING, prompts.items().type());

        SettingDescriptor toolset = settings.get(2);
        assertEquals("mode", toolset.visibleWhen().key());
        assertEquals("advanced", toolset.visibleWhen().equals());
        assertTrue(toolset.options().contains("advanced"));
    }

    @Test
    void nestedMetaSerializesSchemaTreeJsonShape() throws Exception {
        JsonMapper mapper = JsonMapper.builder().build();
        String json = mapper.writeValueAsString(nestedController().meta().getBody());
        assertTrue(json.contains("\"namespace\":\"example\""), json);
        assertTrue(json.contains("\"type\":\"object\""), json);
        assertTrue(json.contains("\"children\""), json);
        assertTrue(json.contains("\"type\":\"array\""), json);
        assertTrue(json.contains("\"items\""), json);
        assertTrue(json.contains("\"visibleWhen\""), json);
        assertTrue(json.contains("\"equals\":\"advanced\""), json);
        // 叶子 JSON 不携带嵌套键（@JsonInclude NON_NULL）
        String leafJson = mapper.writeValueAsString(nestedController().meta().getBody().get(0).settings().get(0).children().get(0));
        assertTrue(!leafJson.contains("children") && !leafJson.contains("items") && !leafJson.contains("visibleWhen"),
                leafJson);
    }

    // ===== P2：PUT 嵌套 body → GET 结构化 JSON =====

    @Test
    void putNestedBodyThenGetReturnsStructuredValue() {
        SettingsController controller = nestedController();
        Map<String, Object> profile = Map.of("name", "bob", "age", 40, "gender", "male");
        controller.set("example", "profile", new SettingsController.SetBody(profile));

        Map<String, Object> values = controller.all("example").getBody();
        assertEquals(profile, values.get("profile"), "嵌套 object PUT 后 all 视图返回结构化 Map");
        assertEquals(Map.of("name", "bob", "age", 40, "gender", "male"),
                controller.get("example", "profile").getBody().get("value"), "GET 单键返回结构化 JSON");
    }
}
