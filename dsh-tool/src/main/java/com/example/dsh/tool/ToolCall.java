package com.example.dsh.tool;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 工具调用参数 — 模型发起的工具调用。
 */
public record ToolCall(
        String id,
        String name,
        Map<String, Object> arguments) {

    public String getString(String key) {
        return getString(key, null);
    }

    public String getString(String key, String defaultValue) {
        Object value = arguments == null ? null : arguments.get(key);
        return value == null ? defaultValue : String.valueOf(value);
    }

    public Integer getInt(String key, Integer defaultValue) {
        Object value = arguments == null ? null : arguments.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public Boolean getBool(String key, Boolean defaultValue) {
        Object value = arguments == null ? null : arguments.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        return "true".equalsIgnoreCase(String.valueOf(value));
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getMap(String key) {
        Object value = arguments == null ? null : arguments.get(key);
        return value instanceof Map ? (Map<String, Object>) value : null;
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getList(String key) {
        Object value = arguments == null ? null : arguments.get(key);
        return value instanceof List ? (List<Map<String, Object>>) value : null;
    }

    /** 获取 List&lt;String&gt; 参数（兼容 {"value": ...} 元素）。 */
    public List<String> getStringList(String key) {
        Object value = arguments == null ? null : arguments.get(key);
        if (value instanceof List<?> list) {
            List<String> result = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> m) {
                    Object v = m.get("value");
                    result.add(v != null ? String.valueOf(v) : String.valueOf(item));
                } else {
                    result.add(String.valueOf(item));
                }
            }
            return result;
        }
        return null;
    }

    public Object get(String key) {
        return arguments == null ? null : arguments.get(key);
    }

    public Double getDouble(String key, Double defaultValue) {
        Object value = arguments == null ? null : arguments.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
