package com.bizfty.anchon.dsh.settings;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P3 secret redact 单测：按描述符树剥离 secret + secrets sidecar（object 属性恒枚举 /
 * array 逐项 / 未设置槽 set=false / 未知键透传 / 非 secret 保留）。
 */
class SettingsRedactorTest {

    /** 示例树：顶层 secret key + object profile（内嵌 secret token）+ array proxies（元素含 secret）。 */
    private List<SettingDescriptor> tree() {
        return List.of(
                SettingDescriptor.withSecret(SettingDescriptor.leaf("apiKey", "string", "API Key", null, null)),
                SettingDescriptor.group("profile", "档案", null,
                        SettingDescriptor.leaf("name", "string", "昵称", null, ""),
                        SettingDescriptor.withSecret(SettingDescriptor.leaf("token", "string", "令牌", null, null))),
                SettingDescriptor.list("proxies", "代理", null,
                        SettingDescriptor.group("", null, null,
                                SettingDescriptor.leaf("host", "string", "主机", null, ""),
                                SettingDescriptor.withSecret(SettingDescriptor.leaf("password", "string", "密码", null, null)))),
                SettingDescriptor.leaf("mode", "enum", "模式", null, "auto"));
    }

    @Test
    void stripsSecretsAndKeepsNonSecrets() {
        Map<String, Object> value = Map.of(
                "apiKey", "sk-123",
                "profile", Map.of("name", "alice", "token", "t-456"),
                "proxies", List.of(Map.of("host", "h1", "password", "p1")),
                "mode", "manual",
                "unknownKey", "透传值");

        SettingsRedactor.Result r = SettingsRedactor.redact(tree(), value);

        Map<String, Object> v = r.value();
        assertFalse(v.containsKey("apiKey"), "顶层 secret 应剥离");
        assertFalse(((Map<?, ?>) v.get("profile")).containsKey("token"), "嵌套 secret 应剥离");
        assertEquals("alice", ((Map<?, ?>) v.get("profile")).get("name"), "非 secret 子键保留");
        assertEquals(List.of(Map.of("host", "h1")), v.get("proxies"), "array 元素 secret 剥离");
        assertEquals("manual", v.get("mode"));
        assertEquals("透传值", v.get("unknownKey"), "未知键透传");
    }

    @Test
    void secretsSidecarListsPositionsAndPresence() {
        Map<String, Object> value = Map.of(
                "apiKey", "sk-123",
                "profile", Map.of("name", "alice"), // token 未设置
                "proxies", List.of(Map.of("host", "h1"))); // password 未设置
        SettingsRedactor.Result r = SettingsRedactor.redact(tree(), value);

        assertEquals(3, r.secrets().size());
        assertTrue(r.secrets().contains(new SettingsRedactor.Secret(List.of("apiKey"), true)));
        assertTrue(r.secrets().contains(new SettingsRedactor.Secret(List.of("profile", "token"), false)),
                "object 属性恒枚举：未设置 secret 槽也要 set=false");
        assertTrue(r.secrets().contains(new SettingsRedactor.Secret(List.of("proxies", "0", "password"), false)),
                "array 元素内 secret 槽：元素存在即记录");
    }

    @Test
    void arrayItemSecretOnlyRecordedWhereElementHoldsIt() {
        Map<String, Object> value = Map.of(
                "proxies", List.of(Map.of("host", "h1", "password", "p1"), Map.of("host", "h2")));
        SettingsRedactor.Result r = SettingsRedactor.redact(tree(), value);

        assertTrue(r.secrets().contains(new SettingsRedactor.Secret(List.of("proxies", "0", "password"), true)));
        assertTrue(r.secrets().contains(new SettingsRedactor.Secret(List.of("proxies", "1", "password"), false)));
        assertEquals(List.of(Map.of("host", "h1"), Map.of("host", "h2")), r.value().get("proxies"));
    }

    @Test
    void missingTopLevelSecretSlotEnumeratedAsUnset() {
        Map<String, Object> value = Map.of("mode", "auto"); // apiKey 整体缺失
        SettingsRedactor.Result r = SettingsRedactor.redact(tree(), value);
        assertTrue(r.secrets().contains(new SettingsRedactor.Secret(List.of("apiKey"), false)));
        assertTrue(r.secrets().contains(new SettingsRedactor.Secret(List.of("profile", "token"), false)));
        assertFalse(r.value().containsKey("apiKey"));
    }

    @Test
    void withSecretSerializesSecretFlagOnWire() throws Exception {
        tools.jackson.databind.json.JsonMapper mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        String json = mapper.writeValueAsString(SettingDescriptor.withSecret(
                SettingDescriptor.leaf("apiKey", "string", "API Key", null, null)));
        assertTrue(json.contains("\"secret\":true"), json);
        assertFalse(mapper.writeValueAsString(SettingDescriptor.leaf("name", "string", "名字", null, null))
                .contains("\"secret\""), "非 secret 不序列化该字段");
    }
}
