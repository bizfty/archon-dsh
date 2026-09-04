package com.bizfty.anchon.dsh.api;

import com.bizfty.anchon.dsh.settings.SettingDescriptor;
import com.bizfty.anchon.dsh.settings.SettingsConflictException;
import com.bizfty.anchon.dsh.settings.SettingsRedactor;
import com.bizfty.anchon.dsh.settings.SettingsService;
import com.bizfty.anchon.dsh.storage.InMemoryStorageBackend;
import com.bizfty.anchon.dsh.storage.StorageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Settings 端点测试：GET /api/settings/meta → 已注册描述符命名空间的
 * 描述符 + redacted view（value/user/revision/secrets/applies）；未注册描述符的不暴露。
 * P2：嵌套 schema（object children/array items/visibleWhen）与 PUT 嵌套 body；
 * P3：secret redact、PUT/POST ops 带 expectedRevision CAS、DELETE unset、409 冲突映射。
 */
class SettingsMetaControllerTest {

    @SuppressWarnings("unchecked")
    private SettingsService service(ObjectProvider<com.bizfty.anchon.dsh.storage.StorageBackend> op) {
        return new SettingsService(new StorageService(op), JsonMapper.builder().build());
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<com.bizfty.anchon.dsh.storage.StorageBackend> backend() {
        ObjectProvider<com.bizfty.anchon.dsh.storage.StorageBackend> op = mock(ObjectProvider.class);
        when(op.orderedStream()).thenReturn(Stream.of(new InMemoryStorageBackend()));
        return op;
    }

    private SettingsController controller() {
        SettingsService service = service(backend());
        service.registerDefaults("agent", Map.of("temperature", 0.7, "max-steps", 25));
        service.registerDescriptor("agent", SettingDescriptor.leaf(
                "temperature", "number", "温度", "模型采样温度", 0.7, null, 0.0, 2.0, 0.1));
        service.registerDescriptor("agent", SettingDescriptor.leaf(
                "max-steps", "integer", "最大步数", "单 turn 防失控兜底", 25, null, 1.0, 10000.0, 1.0));
        service.registerDefaults("hidden", Map.of("x", 1));
        return new SettingsController(service);
    }

    /** 嵌套描述符 namespace（object children / array items / visibleWhen 联动）。 */
    private SettingsController nestedController() {
        SettingsService service = service(backend());
        service.registerDescriptor("example", SettingDescriptor.group("profile", "用户档案", "个人信息",
                SettingDescriptor.leaf("name", SettingDescriptor.Types.STRING, "昵称", "显示名", ""),
                SettingDescriptor.number("age", SettingDescriptor.Types.INTEGER, "年龄", null, 18, 0.0, 150.0, 1.0),
                SettingDescriptor.choice("gender", "性别", null, "unknown", List.of("male", "female", "unknown"))));
        service.registerDescriptor("example", SettingDescriptor.list("prompts", "提示集", "自定义提示列表",
                SettingDescriptor.leaf("prompt", SettingDescriptor.Types.STRING, "提示", null, "")));
        service.registerDescriptor("example", SettingDescriptor.withVisible(
                SettingDescriptor.choice("toolset", "工具集", null, "basic", List.of("basic", "advanced")),
                "mode", "advanced"));
        return new SettingsController(service);
    }

    /** secret namespace：apiKey（secret）+ url（普通）均有用户覆盖。 */
    private SettingsController secretController() {
        SettingsService service = service(backend());
        service.registerDefaults("creds", Map.of("apiKey", "sk-default", "url", "https://example.com"));
        service.registerDescriptor("creds", SettingDescriptor.withSecret(
                SettingDescriptor.leaf("apiKey", "string", "API Key", null, "sk-default")));
        service.registerDescriptor("creds", SettingDescriptor.leaf("url", "string", "URL", null, "https://example.com"));
        service.set("creds", "apiKey", "sk-user");
        service.set("creds", "url", "https://user.example.com");
        return new SettingsController(service);
    }

    // ===== meta view 基本形状 =====

    @Test
    void metaListsOnlyDescribedNamespacesWithDescriptorAndValue() {
        SettingsController controller = controller();
        List<SettingsController.SettingsMetaDto> metas = controller.meta().getBody();
        assertEquals(1, metas.size(), "未注册描述符的 namespace 不暴露");
        SettingsController.SettingsMetaDto agent = metas.get(0);

        assertEquals("agent", agent.namespace());
        assertEquals(List.of("temperature", "max-steps"),
                agent.settings().stream().map(SettingDescriptor::key).toList(), "描述符保序");
        assertEquals(0.7, agent.value().get("temperature"), "默认值并入 redacted resolved");
        assertEquals("live", agent.applies(), "缺省 applies=live");
        assertEquals(0L, agent.revision());
        assertEquals("温度", agent.settings().get(0).label());
        assertEquals(0.0, agent.settings().get(0).min());
    }

    @Test
    void metaValueReflectsOverrideAndUserPresence() {
        SettingsController controller = controller();
        controller.set("agent", "temperature", new SettingsController.SetBody(0.3, null));

        SettingsController.SettingsMetaDto agent = controller.meta().getBody().get(0);
        assertEquals(0.3, agent.value().get("temperature"), "覆盖值反映到 view.value");
        assertTrue(agent.user().containsKey("temperature"), "覆盖键在 user 层（presence）");
        assertFalse(agent.user().containsKey("max-steps"), "未覆盖键不在 user 层");
        assertTrue(controller.all("agent").getStatusCode().is2xxSuccessful());
    }

    // ===== P2：嵌套 schema meta 形状 =====

    @Test
    void nestedMetaCarriesObjectChildrenArrayItemsAndVisibleWhen() {
        List<SettingsController.SettingsMetaDto> metas = nestedController().meta().getBody();
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
    }

    @Test
    void putNestedBodyThenGetReturnsStructuredValue() {
        SettingsController controller = nestedController();
        Map<String, Object> profile = Map.of("name", "bob", "age", 40, "gender", "male");
        controller.set("example", "profile", new SettingsController.SetBody(profile, null));

        assertEquals(profile, controller.all("example").getBody().get("profile"), "嵌套 object PUT 后 all 返回结构化 Map");
        assertEquals(profile, controller.get("example", "profile").getBody().get("value"));
    }

    // ===== P3：secret redact view =====

    @Test
    void metaViewRedactsSecretValuesButKeepsSecretsSidecar() {
        SettingsController.SettingsMetaDto creds = secretController().meta().getBody().get(0);

        assertEquals("creds", creds.namespace());
        assertFalse(creds.value().containsKey("apiKey"), "resolved view 不得含 secret 明文");
        assertEquals("https://user.example.com", creds.value().get("url"), "非 secret 正常下发");
        assertFalse(creds.user().containsKey("apiKey"), "user view 不得含 secret 明文");
        assertTrue(creds.user().containsKey("url"), "user presence 仅对非 secret 可见");

        assertTrue(creds.secrets().contains(new SettingsRedactor.Secret(List.of("apiKey"), true)),
                "secret 槽 set=true（resolved 持值）");
        assertTrue(creds.settings().get(0).secret() == Boolean.TRUE, "描述符携带 secret 声明（write-only 渲染）");
    }

    // ===== P3：CAS / ops / delete =====

    @Test
    void staleExpectedRevisionThrowsConflict() {
        SettingsController controller = controller();
        long rev1 = controller.set("agent", "temperature", new SettingsController.SetBody(0.3, 0L))
                .getBody().get("revision") instanceof Number n ? n.longValue() : -1;
        assertEquals(1L, rev1);

        SettingsConflictException ex = assertThrows(SettingsConflictException.class,
                () -> controller.set("agent", "temperature", new SettingsController.SetBody(0.5, 0L)),
                "过期 expectedRevision 应拒绝");
        assertEquals(0L, ex.getExpected());
        assertEquals(1L, ex.getActual());
    }

    @Test
    void opsEndpointMutatesPathAndReturnsRevision() {
        SettingsController controller = controller();
        controller.ops("agent", new SettingsController.OpsBody(List.of(
                new SettingsController.OpDto("set", List.of("temperature"), 0.4)), 0L));

        assertEquals(0.4, controller.get("agent", "temperature").getBody().get("value"));
        assertEquals(1L, controller.meta().getBody().get(0).revision());

        // unset → 回默认 + revision 再 +1
        controller.ops("agent", new SettingsController.OpsBody(List.of(
                new SettingsController.OpDto("unset", List.of("temperature"), null)), 1L));
        assertEquals(0.7, controller.get("agent", "temperature").getBody().get("value"), "unset 回默认");
    }

    @Test
    void deleteEndpointUnsetsKey() {
        SettingsController controller = controller();
        controller.set("agent", "temperature", new SettingsController.SetBody(0.9, null));
        controller.delete("agent", "temperature");
        assertEquals(0.7, controller.get("agent", "temperature").getBody().get("value"), "DELETE = 恢复默认");
        var meta = controller.meta().getBody().get(0);
        assertTrue(meta.user() == null || !meta.user().containsKey("temperature"), "DELETE 后无 user 覆盖");
    }

    @Test
    void globalHandlerMapsConflictTo409WithCodeAndActual() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        SettingsConflictException ex = new SettingsConflictException("agent", 2L, 5L);
        var resp = handler.settingsConflict(ex);
        assertEquals(HttpStatus.CONFLICT, resp.getStatusCode());
        assertEquals("SETTINGS_CONFLICT", resp.getBody().get("code"));
        assertEquals("agent", resp.getBody().get("namespace"));
        assertEquals(2L, ((Number) resp.getBody().get("expected")).longValue());
        assertEquals(5L, ((Number) resp.getBody().get("actual")).longValue());
    }

    @Test
    void illegalOpsPathMapsToBadRequest() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        var resp = handler.badRequest(new IllegalArgumentException("数组越界"));
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertEquals("bad_request", resp.getBody().get("error"));
    }
}
