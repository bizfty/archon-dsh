package com.bizfty.anchon.dsh.settings;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * secret 结构剥离（对齐官方 settings redact.ts）：按描述符树把 {@code secret=true} 字段的值
 * 从 detached 副本中移除，并记录每个 secret 槽位（object 属性恒枚举——即使容器缺失/字段未设
 * 也通知前端槽位存在；array 元素仅实际存在处记录）；未描述键原样透传。wire 边界必须经此剥离。
 */
public final class SettingsRedactor {

    /** 一个被剥离的 secret 槽位：path 自值根寻址；set = 剥离前是否持有值。 */
    public record Secret(List<String> path, boolean set) {
    }

    /** 剥离结果：无 secret 的 detached 值副本 + 有序 secret 槽位。 */
    public record Result(Map<String, Object> value, List<Secret> secrets) {
    }

    private SettingsRedactor() {
    }

    private static final Object ABSENT = new Object();

    /**
     * 按顶层描述符剥离 value（Map 根）中的 secret；描述符覆盖的键（无论值中是否出现）都会
     * 走一遍（object 属性恒枚举），无描述符的键透传。
     *
     * @param descriptors 顶层描述符（保序）
     * @param value       redact 前的值（resolved 或 user 层均可，须 JSON 兼容 Map；可 null）
     */
    public static Result redact(List<SettingDescriptor> descriptors, Map<String, Object> value) {
        Map<String, SettingDescriptor> byKey = byKey(descriptors);
        List<Secret> secrets = new ArrayList<>();
        Map<String, Object> rebuilt = new LinkedHashMap<>();
        // 描述符覆盖键：统一 walk（缺失键 present=false 仍下钻枚举 object 内 secret 槽）
        for (SettingDescriptor desc : descriptors) {
            boolean present = value != null && value.containsKey(desc.key());
            Object stripped = walk(desc, present, present ? value.get(desc.key()) : null,
                    List.of(desc.key()), secrets);
            if (stripped != ABSENT) {
                rebuilt.put(desc.key(), stripped);
            }
        }
        // 未知键透传（不丢失非 schema 数据）
        if (value != null) {
            for (Map.Entry<String, Object> e : value.entrySet()) {
                if (!byKey.containsKey(e.getKey())) {
                    rebuilt.put(e.getKey(), e.getValue());
                }
            }
        }
        return new Result(rebuilt, secrets);
    }

    /** 递归剥离单个描述符覆盖的值；secret 或"整体缺失的非容器"返回 ABSENT（调用方不放回）。 */
    private static Object walk(SettingDescriptor desc, boolean present, Object value,
                               List<String> path, List<Secret> secrets) {
        if (desc == null) {
            return value == null ? ABSENT : value;
        }
        if (Boolean.TRUE.equals(desc.secret())) {
            secrets.add(new Secret(path, present));
            return ABSENT;
        }
        String type = desc.type();
        if (SettingDescriptor.Types.OBJECT.equals(type)) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = value instanceof Map ? (Map<String, Object>) value : null;
            Map<String, SettingDescriptor> children = byKey(desc.children());
            Map<String, Object> rebuilt = new LinkedHashMap<>();
            if (map != null) {
                // 未知子键透传
                for (Map.Entry<String, Object> e : map.entrySet()) {
                    if (!children.containsKey(e.getKey())) {
                        rebuilt.put(e.getKey(), e.getValue());
                    }
                }
            }
            // 已知子键逐描述符剥离（缺失子键/缺失容器都下钻枚举 secret 槽）
            for (SettingDescriptor child : desc.children()) {
                boolean childPresent = map != null && map.containsKey(child.key());
                Object childValue = childPresent ? map.get(child.key()) : null;
                Object stripped = walk(child, childPresent, childValue, concat(path, child.key()), secrets);
                if (stripped != ABSENT) {
                    rebuilt.put(child.key(), stripped);
                }
            }
            // 容器整体缺失且无内容 → ABSENT；容器存在（即使空）→ 保留空 Map
            return map == null && rebuilt.isEmpty() ? ABSENT : rebuilt;
        }
        if (SettingDescriptor.Types.ARRAY.equals(type)) {
            if (!(value instanceof List<?> list)) {
                return ABSENT;
            }
            List<Object> rebuilt = new ArrayList<>(list.size());
            SettingDescriptor items = desc.items();
            for (int i = 0; i < list.size(); i++) {
                Object element = list.get(i);
                if (items == null) {
                    rebuilt.add(element);
                    continue;
                }
                Object stripped = walk(items, true, element, concat(path, String.valueOf(i)), secrets);
                rebuilt.add(stripped == ABSENT ? null : stripped);
            }
            return rebuilt;
        }
        // 叶子（非 secret）：值缺失整体 ABSENT；其余原样
        return value == null ? ABSENT : value;
    }

    private static Map<String, SettingDescriptor> byKey(List<SettingDescriptor> descriptors) {
        if (descriptors == null || descriptors.isEmpty()) {
            return Map.of();
        }
        Map<String, SettingDescriptor> map = new LinkedHashMap<>();
        for (SettingDescriptor d : descriptors) {
            map.put(d.key(), d);
        }
        return map;
    }

    private static List<String> concat(List<String> path, String segment) {
        List<String> next = new ArrayList<>(path.size() + 1);
        next.addAll(path);
        next.add(segment);
        return next;
    }
}
