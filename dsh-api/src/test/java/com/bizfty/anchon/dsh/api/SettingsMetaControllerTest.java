package com.bizfty.anchon.dsh.api;

import com.bizfty.anchon.dsh.settings.SettingDescriptor;
import com.bizfty.anchon.dsh.settings.SettingsService;
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
 * Settings meta 端点测试：GET /api/settings/meta → 已注册描述符命名空间的
 * 描述符 + 当前值合并视图；未注册描述符的命名空间不暴露。
 */
class SettingsMetaControllerTest {

    @SuppressWarnings("unchecked")
    private SettingsController controller() {
        ObjectProvider<com.bizfty.anchon.dsh.storage.StorageBackend> op = mock(ObjectProvider.class);
        when(op.orderedStream()).thenReturn(Stream.of(new InMemoryStorageBackend()));
        SettingsService service = new SettingsService(new StorageService(op));
        service.registerDefaults("agent", Map.of("temperature", 0.7, "max-steps", 25));
        service.registerDescriptor("agent", new SettingDescriptor(
                "temperature", "number", "温度", "模型采样温度", 0.7, null, 0.0, 2.0, 0.1));
        service.registerDescriptor("agent", new SettingDescriptor(
                "max-steps", "integer", "最大步数", "单 turn 防失控兜底", 25, null, 1.0, 10000.0, 1.0));
        service.registerDefaults("hidden", Map.of("x", 1));
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
}
