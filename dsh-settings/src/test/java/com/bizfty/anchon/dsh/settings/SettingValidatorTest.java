package com.bizfty.anchon.dsh.settings;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P3 写校验单测：按描述符树强校验已知键（number/integer 数值 + min/max + 整数、boolean、
 * enum∈options、string）、object 递归、array items、未知键宽容、无描述符跳过。
 */
class SettingValidatorTest {

    private List<SettingDescriptor> tree() {
        return List.of(
                SettingDescriptor.number("temperature", "number", "温度", null, 0.7, 0.0, 2.0, 0.1),
                SettingDescriptor.number("max-steps", "integer", "步数", null, 25, 1.0, 10000.0, 1.0),
                SettingDescriptor.choice("mode", "模式", null, "auto", List.of("auto", "manual")),
                SettingDescriptor.leaf("title", "string", "标题", null, ""),
                SettingDescriptor.leaf("enabled", "boolean", "开关", null, false),
                SettingDescriptor.group("profile", "档案", null,
                        SettingDescriptor.number("age", "integer", "年龄", null, 18, 0.0, 150.0, 1.0),
                        SettingDescriptor.leaf("nickname", "string", "昵称", null, "")),
                SettingDescriptor.list("prompts", "提示", null,
                        SettingDescriptor.leaf("prompt", "string", "提示", null, "")),
                SettingDescriptor.list("proxies", "代理", null,
                        SettingDescriptor.group("", null, null,
                                SettingDescriptor.leaf("host", "string", "主机", null, ""),
                                SettingDescriptor.number("port", "integer", "端口", null, 8080, 1.0, 65535.0, 1.0))));
    }

    private void assertInvalid(List<SettingDescriptor> tree, Map<String, Object> doc, String pathFragment) {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> SettingValidator.validate(tree, doc));
        assertTrue(ex.getMessage().contains(pathFragment), ex.getMessage());
    }

    @Test
    void acceptsWellFormedDocument() {
        assertDoesNotThrow(() -> SettingValidator.validate(tree(), Map.of(
                "temperature", 0.7, "max-steps", 25, "mode", "auto",
                "title", "hi", "enabled", true,
                "profile", Map.of("age", 30, "nickname", "alice"),
                "prompts", List.of("a", "b"),
                "proxies", List.of(Map.of("host", "h1", "port", 8080)))));
    }

    @Test
    void rejectsNumberTypeAndBoundViolations() {
        assertInvalid(tree(), Map.of("temperature", "0.7"), "temperature");
        assertInvalid(tree(), Map.of("temperature", -1.0), "temperature");
        assertInvalid(tree(), Map.of("temperature", 2.5), "temperature");
    }

    @Test
    void rejectsFractionalInteger() {
        assertInvalid(tree(), Map.of("max-steps", 2.5), "max-steps");
        assertDoesNotThrow(() -> SettingValidator.validate(tree(), Map.of("max-steps", 2.0)));
    }

    @Test
    void rejectsBooleanStringAndEnumOutsideOptions() {
        assertInvalid(tree(), Map.of("enabled", "true"), "enabled");
        assertInvalid(tree(), Map.of("mode", "turbo"), "mode");
        assertInvalid(tree(), Map.of("title", 42), "title");
    }

    @Test
    void recursesIntoObjectChildrenAndArrayItems() {
        assertInvalid(tree(), Map.of("profile", Map.of("age", 999)), "profile.age");
        assertInvalid(tree(), Map.of("profile", Map.of("nickname", 7)), "profile.nickname");
        assertInvalid(tree(), Map.of("prompts", List.of("ok", 3)), "prompts.1");
        assertInvalid(tree(), Map.of("proxies", List.of(Map.of("host", "h", "port", 70000))), "proxies.0.port");
        assertInvalid(tree(), Map.of("profile", "not-object"), "profile");
        assertInvalid(tree(), Map.of("prompts", "not-array"), "prompts");
    }

    @Test
    void unknownKeysPassThroughTolerantly() {
        assertDoesNotThrow(() -> SettingValidator.validate(tree(), Map.of(
                "temperature", 0.5, "future-key", 123, "another", Map.of("x", 1))));
    }

    @Test
    void emptyOrNullDescriptorsSkipsValidation() {
        assertDoesNotThrow(() -> SettingValidator.validate(List.of(), Map.of("anything", "goes")));
        assertDoesNotThrow(() -> SettingValidator.validate(null, Map.of("anything", "goes")));
        assertDoesNotThrow(() -> SettingValidator.validate(tree(), null));
    }
}
