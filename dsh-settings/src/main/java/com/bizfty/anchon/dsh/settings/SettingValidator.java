package com.bizfty.anchon.dsh.settings;

import java.util.List;
import java.util.Map;

/**
 * 描述符树写校验（对齐官方 write 过 schema）：写路径落盘前按描述符树强校验 user 文档——
 * 已知键 leaf 类型强校验（number/integer 数值 + min/max、integer 整数、boolean、enum∈options、
 * string 为字符串）；object 递归 children；array 逐元素按 items；未知键宽容透传；
 * 无描述符命名空间跳过（由调用方判断）。校验失败抛 {@link IllegalArgumentException}。
 */
public final class SettingValidator {

    private SettingValidator() {
    }

    /**
     * 校验整个 user 文档；非法值抛 IllegalArgumentException（消息含出错路径）。
     *
     * @param descriptors 该命名空间的顶层描述符；null/空 → 不做任何校验
     * @param doc         user 文档（JSON 兼容）
     */
    public static void validate(List<SettingDescriptor> descriptors, Map<String, Object> doc) {
        if (descriptors == null || descriptors.isEmpty() || doc == null) {
            return;
        }
        Map<String, SettingDescriptor> byKey = byKey(descriptors);
        for (Map.Entry<String, Object> e : doc.entrySet()) {
            SettingDescriptor desc = byKey.get(e.getKey());
            if (desc != null) {
                validateValue(desc, e.getValue(), List.of(e.getKey()));
            }
            // 未知键宽容透传
        }
    }

    private static void validateValue(SettingDescriptor desc, Object value, List<String> path) {
        String type = desc.type();
        switch (type == null ? "" : type) {
            case SettingDescriptor.Types.NUMBER, SettingDescriptor.Types.INTEGER -> {
                if (!(value instanceof Number n)) {
                    throw error(path, type + " 须为数值，实际: " + describe(value));
                }
                double d = n.doubleValue();
                if (desc.min() != null && d < desc.min()) {
                    throw error(path, "低于下界 " + desc.min());
                }
                if (desc.max() != null && d > desc.max()) {
                    throw error(path, "超过上界 " + desc.max());
                }
                if (SettingDescriptor.Types.INTEGER.equals(type) && d != Math.floor(d)) {
                    throw error(path, "integer 须为整数，实际: " + d);
                }
            }
            case SettingDescriptor.Types.BOOLEAN -> {
                if (!(value instanceof Boolean)) {
                    throw error(path, "boolean 须为 true/false，实际: " + describe(value));
                }
            }
            case SettingDescriptor.Types.ENUM -> {
                if (!(value instanceof String s) || desc.options() == null || !desc.options().contains(s)) {
                    throw error(path, "enum 值不在选项内: " + describe(value)
                            + (desc.options() == null ? "" : "（可选 " + desc.options() + "）"));
                }
            }
            case SettingDescriptor.Types.STRING -> {
                if (!(value instanceof String)) {
                    throw error(path, "string 须为字符串，实际: " + describe(value));
                }
            }
            case SettingDescriptor.Types.OBJECT -> {
                if (!(value instanceof Map<?, ?> raw)) {
                    throw error(path, "object 须为对象，实际: " + describe(value));
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> map = (Map<String, Object>) raw;
                Map<String, SettingDescriptor> children = byKey(desc.children());
                for (Map.Entry<String, Object> e : map.entrySet()) {
                    SettingDescriptor child = children.get(e.getKey());
                    if (child != null) {
                        validateValue(child, e.getValue(), append(path, e.getKey()));
                    }
                    // object 内未知子键宽容透传
                }
            }
            case SettingDescriptor.Types.ARRAY -> {
                if (!(value instanceof List<?> list)) {
                    throw error(path, "array 须为数组，实际: " + describe(value));
                }
                if (desc.items() == null) {
                    return;
                }
                for (int i = 0; i < list.size(); i++) {
                    validateValue(desc.items(), list.get(i), append(path, String.valueOf(i)));
                }
            }
            default -> {
                // 未知 type：不做校验（宽容，防未来类型误伤）
            }
        }
    }

    private static Map<String, SettingDescriptor> byKey(List<SettingDescriptor> descriptors) {
        if (descriptors == null || descriptors.isEmpty()) {
            return Map.of();
        }
        Map<String, SettingDescriptor> map = new java.util.LinkedHashMap<>();
        for (SettingDescriptor d : descriptors) {
            map.put(d.key(), d);
        }
        return map;
    }

    private static List<String> append(List<String> path, String segment) {
        List<String> next = new java.util.ArrayList<>(path.size() + 1);
        next.addAll(path);
        next.add(segment);
        return next;
    }

    private static IllegalArgumentException error(List<String> path, String message) {
        return new IllegalArgumentException("设置校验失败 [" + String.join(".", path) + "]: " + message);
    }

    private static String describe(Object value) {
        if (value == null) {
            return "null";
        }
        return value.getClass().getSimpleName() + "(" + String.valueOf(value).substring(0,
                Math.min(String.valueOf(value).length(), 60)) + ")";
    }
}
