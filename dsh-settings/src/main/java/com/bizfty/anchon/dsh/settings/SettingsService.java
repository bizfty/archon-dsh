package com.bizfty.anchon.dsh.settings;

import com.bizfty.anchon.dsh.storage.StorageService;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 命名空间设置（对应 DSH settings：schema 默认 > 用户覆盖的分层解析）。
 * <p>
 * 覆盖值经 {@link StorageService} 持久化（命名空间前缀 settings.）；
 * 默认值由 registerDefaults 注册（模块装配期）。
 * <p>
 * P2：嵌套值（Map/List，来自 object/array 设置项）以 JSON 字符串持久化；
 * 标量沿用 {@link String#valueOf}（存量存储文本零迁移）。读取时统一反序列化
 * 为结构化对象，get/all/meta 链路自动获得 Map/List 值。
 */
@Service
public class SettingsService {

    private final StorageService storage;
    private final ObjectMapper mapper;
    private final Map<String, Map<String, Object>> defaults = new ConcurrentHashMap<>();
    private final Map<String, Map<String, SettingDescriptor>> descriptors = new ConcurrentHashMap<>();

    public SettingsService(StorageService storage, ObjectMapper mapper) {
        this.storage = storage;
        this.mapper = mapper;
    }

    /** 注册命名空间默认值（schema 层）。 */
    public void registerDefaults(String namespace, Map<String, Object> defaultValues) {
        defaults.put(namespace, new LinkedHashMap<>(defaultValues));
    }

    /** 注册命名空间下某键的描述符（schema 层；与默认值/覆盖独立，可单独注册）。 */
    public void registerDescriptor(String namespace, SettingDescriptor descriptor) {
        descriptors.computeIfAbsent(namespace, k ->
                        Collections.synchronizedMap(new LinkedHashMap<>()))
                .put(descriptor.key(), descriptor);
    }

    /** 某命名空间的描述符列表（注册顺序保序）；未注册 → 空列表。 */
    public List<SettingDescriptor> describe(String namespace) {
        Map<String, SettingDescriptor> ns = descriptors.get(namespace);
        if (ns == null) {
            return List.of();
        }
        synchronized (ns) {
            return List.copyOf(ns.values());
        }
    }

    /** 已注册描述符的命名空间（字典序；供 /meta 枚举，未注册描述符的不暴露）。 */
    public List<String> describedNamespaces() {
        return descriptors.keySet().stream().sorted().toList();
    }

    /** 解析值：用户覆盖 > 默认。 */
    public Object get(String namespace, String key) {
        Object override = storage.get("settings." + namespace, key).map(this::parse).orElse(null);
        if (override != null) {
            return override;
        }
        return defaults.getOrDefault(namespace, Map.of()).get(key);
    }

    public String getString(String namespace, String key, String fallback) {
        Object value = get(namespace, key);
        return value == null ? fallback : String.valueOf(value);
    }

    public int getInt(String namespace, String key, int fallback) {
        Object value = get(namespace, key);
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    /** 设置覆盖值（持久化）。 */
    public void set(String namespace, String key, Object value) {
        storage.put("settings." + namespace, key, encode(value));
    }

    /** 合并视图（默认 + 覆盖）。 */
    public Map<String, Object> all(String namespace) {
        Map<String, Object> merged = new LinkedHashMap<>(defaults.getOrDefault(namespace, Map.of()));
        for (String key : storage.keys("settings." + namespace)) {
            Object value = get(namespace, key);
            merged.put(key, value);
        }
        return merged;
    }

    /** 持久化编码：嵌套对象/数组 → JSON 字符串；标量沿用 String.valueOf（存量标量零变化）。 */
    private String encode(Object value) {
        if (value instanceof Map || value instanceof List) {
            return mapper.writeValueAsString(value);
        }
        return String.valueOf(value);
    }

    /** 覆盖值解码：JSON 文本（去除首尾空白后以 { 或 [ 开头）反序列化为 Map/List/标量混合；
     * 解析失败回落原文本（存量脏数据兜底，如误存的 "{a=1}"）。 */
    private Object parse(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            try {
                return mapper.readValue(trimmed, Object.class);
            } catch (Exception ignored) {
                // 非合法 JSON → 回落原文本
            }
        }
        if ("true".equalsIgnoreCase(text)) {
            return true;
        }
        if ("false".equalsIgnoreCase(text)) {
            return false;
        }
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException ignored) {
        }
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException ignored) {
        }
        return text;
    }
}
