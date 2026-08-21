package com.example.dsh.util;

import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

/**
 * JSON 工具 — Jackson 3（Spring Boot 4 默认，包名 tools.jackson）封装。
 * <p>
 * 对应 DSH {@code util/*} 中的序列化基础；统一 ObjectMapper 配置
 * （JSR-310 内置且默认 ISO 日期、未知属性容错），避免各处自建 mapper 漂移。
 */
@Component
public final class JsonUtils {

    private final ObjectMapper mapper;

    public JsonUtils() {
        this.mapper = JsonMapper.builder()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
    }

    public JsonUtils(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public ObjectMapper mapper() {
        return mapper;
    }

    /** 对象 → JSON 字符串；null 返回 "null"。 */
    public String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JacksonException e) {
            throw new JsonUtilsException("序列化失败: " + e.getMessage(), e);
        }
    }

    /** 对象 → 格式化 JSON 字符串（调试用）。 */
    public String toPrettyJson(Object value) {
        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (JacksonException e) {
            throw new JsonUtilsException("序列化失败: " + e.getMessage(), e);
        }
    }

    /** JSON 字符串 → Map。 */
    public Map<String, Object> toMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return mapper.readValue(json, new TypeReference<>() {
            });
        } catch (JacksonException e) {
            throw new JsonUtilsException("解析 JSON 失败: " + e.getMessage(), e);
        }
    }

    public List<Map<String, Object>> toList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return mapper.readValue(json, new TypeReference<>() {
            });
        } catch (JacksonException e) {
            throw new JsonUtilsException("解析 JSON 失败: " + e.getMessage(), e);
        }
    }

    public <T> T fromJson(String json, Class<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (JacksonException e) {
            throw new JsonUtilsException("解析 JSON 失败: " + e.getMessage(), e);
        }
    }

    /** JSON 数组字符串 → List&lt;T&gt;。 */
    public <T> List<T> fromJsonList(String json, Class<T> itemType) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return mapper.readValue(json,
                    mapper.getTypeFactory().constructCollectionType(List.class, itemType));
        } catch (JacksonException e) {
            throw new JsonUtilsException("解析 JSON 数组失败: " + e.getMessage(), e);
        }
    }

    public JsonNode readTree(String json) {
        try {
            return mapper.readTree(json);
        } catch (JacksonException e) {
            throw new JsonUtilsException("解析 JSON 失败: " + e.getMessage(), e);
        }
    }

    /** 兼容 JSON 字符串 / 字符串数组 → List&lt;String&gt;。 */
    public List<String> toStringList(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of(String.valueOf(value));
    }

    /** 解析失败时抛出（unchecked），不吞异常。 */
    public static final class JsonUtilsException extends RuntimeException {
        public JsonUtilsException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
