package com.bizfty.anchon.dsh.hostbridge.json;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Jackson 3 (tools.jackson) 单例与窄工具面。A2 host 协议全部 JSON 经此编解码，
 * 保证信封/帧 exact-key 形状与官方 wire（docs/contract-host-a2.md）一致。
 */
public final class Json {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private Json() {
    }

    /** 解析一段 JSON 文本；非法返回 null（由调用方按 400 处理）。 */
    public static JsonNode parse(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (Exception e) {
            return null;
        }
    }

    /** 把任意 JsonNode 序列化为紧凑 JSON 文本。 */
    public static String write(JsonNode node) {
        try {
            return MAPPER.writeValueAsString(node);
        } catch (Exception e) {
            throw new IllegalStateException("hostbridge: JSON 序列化失败", e);
        }
    }

    /** 新建对象节点。 */
    public static ObjectNode object() {
        return MAPPER.createObjectNode();
    }


    /** 把任意 Java 值（String/Number/Boolean/Map/List/JsonNode/null）转为 JsonNode。 */
    public static JsonNode value(Object value) {
        if (value == null) return MAPPER.getNodeFactory().nullNode();
        if (value instanceof JsonNode node) return node;
        try {
            return MAPPER.valueToTree(value);
        } catch (Exception e) {
            throw new IllegalStateException("hostbridge: 值转 JSON 失败: " + value, e);
        }
    }

    /** 构造数组节点（元素逐个经 {@link #value}）。 */
    public static tools.jackson.databind.node.ArrayNode array(Object... items) {
        tools.jackson.databind.node.ArrayNode array = MAPPER.createArrayNode();
        for (Object item : items) array.add(value(item));
        return array;
    }

    /** 把 JsonNode 转为 Java 值（object→LinkedHashMap、array→ArrayList、scalar→对应类型）。 */
    public static Object toJava(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (node.isTextual()) return node.asText();
        if (node.isBoolean()) return node.asBoolean();
        if (node.isIntegralNumber()) return node.asLong();
        if (node.isFloatingPointNumber()) return node.asDouble();
        if (node.isArray()) {
            java.util.List<Object> list = new java.util.ArrayList<>();
            for (JsonNode child : node) list.add(toJava(child));
            return list;
        }
        java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
        for (var entry : node.properties()) map.put(entry.getKey(), toJava(entry.getValue()));
        return map;
    }

    /** 从文本新建对象节点（语义校验后使用）；非法返回 null。 */
    public static ObjectNode parseObject(String text) {
        JsonNode node = parse(text);
        return node != null && node.isObject() ? (ObjectNode) node : null;
    }
}
