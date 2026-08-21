package com.example.dsh.settings;

import com.example.dsh.storage.StorageService;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 命名空间设置（对应 DSH settings：schema 默认 > 用户覆盖的分层解析）。
 * <p>
 * 覆盖值经 {@link StorageService} 持久化（命名空间前缀 settings.）；
 * 默认值由 registerDefaults 注册（模块装配期）。
 */
@Service
public class SettingsService {

    private final StorageService storage;
    private final Map<String, Map<String, Object>> defaults = new ConcurrentHashMap<>();

    public SettingsService(StorageService storage) {
        this.storage = storage;
    }

    /** 注册命名空间默认值（schema 层）。 */
    public void registerDefaults(String namespace, Map<String, Object> defaultValues) {
        defaults.put(namespace, new LinkedHashMap<>(defaultValues));
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
        storage.put("settings." + namespace, key, String.valueOf(value));
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

    private Object parse(String text) {
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
