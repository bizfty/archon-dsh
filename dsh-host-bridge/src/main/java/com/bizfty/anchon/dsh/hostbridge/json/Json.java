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

    /** 从文本新建对象节点（语义校验后使用）；非法返回 null。 */
    public static ObjectNode parseObject(String text) {
        JsonNode node = parse(text);
        return node != null && node.isObject() ? (ObjectNode) node : null;
    }
}
