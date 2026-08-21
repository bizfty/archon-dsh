package com.example.dsh.tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具 OpenAPI JSON Schema — 注入模型 function calling 声明。
 */
public record ToolSchema(
        String name,
        String description,
        Map<String, Parameter> parameters,
        List<String> required) {

    /**
     * 参数定义。
     *
     * @param type             JSON Schema 类型 (string/number/integer/boolean/array/object)
     * @param description      参数说明
     * @param itemsType        数组元素类型 (type=array 时；'object' 时配合 itemsProperties)
     * @param itemsProperties  对象数组元素的对象属性 (type=array 且 itemsType=object 时)
     * @param itemsRequired    对象数组元素必填字段
     */
    public record Parameter(String type, String description, String itemsType,
                            Map<String, Parameter> itemsProperties, List<String> itemsRequired) {
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 生成 OpenAI function-calling 的 input schema（object 根 + properties + required）。 */
    public Map<String, Object> inputSchema() {
        Map<String, Object> parametersJson = new LinkedHashMap<>();
        parametersJson.put("type", "object");
        Map<String, Object> props = new LinkedHashMap<>();
        for (Map.Entry<String, Parameter> entry : parameters.entrySet()) {
            Parameter p = entry.getValue();
            Map<String, Object> prop = new LinkedHashMap<>();
            prop.put("type", p.type());
            if (p.description() != null) {
                prop.put("description", p.description());
            }
            if ("array".equals(p.type()) && p.itemsType() != null) {
                Map<String, Object> items = new LinkedHashMap<>();
                items.put("type", p.itemsType());
                if ("object".equals(p.itemsType()) && p.itemsProperties() != null
                        && !p.itemsProperties().isEmpty()) {
                    Map<String, Object> itemProps = new LinkedHashMap<>();
                    for (Map.Entry<String, Parameter> ip : p.itemsProperties().entrySet()) {
                        Map<String, Object> ipv = new LinkedHashMap<>();
                        ipv.put("type", ip.getValue().type());
                        if (ip.getValue().description() != null) {
                            ipv.put("description", ip.getValue().description());
                        }
                        itemProps.put(ip.getKey(), ipv);
                    }
                    items.put("properties", itemProps);
                    if (p.itemsRequired() != null && !p.itemsRequired().isEmpty()) {
                        items.put("required", p.itemsRequired());
                    }
                }
                prop.put("items", items);
            }
            props.put(entry.getKey(), prop);
        }
        parametersJson.put("properties", props);
        if (!required.isEmpty()) {
            parametersJson.put("required", required);
        }
        return parametersJson;
    }

    public static final class Builder {
        private String name;
        private String description;
        private final Map<String, Parameter> parameters = new LinkedHashMap<>();
        private final List<String> required = new java.util.ArrayList<>();

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder addParameter(String key, String type, String description) {
            parameters.put(key, new Parameter(type, description, null, null, null));
            return this;
        }

        public Builder addArrayParameter(String key, String description, String itemsType) {
            parameters.put(key, new Parameter("array", description, itemsType, null, null));
            return this;
        }

        /** 对象数组参数（itemsType=object + 元素属性）。 */
        public Builder addObjectArrayParameter(String key, String description,
                                               Map<String, Parameter> itemsProperties,
                                               String... itemsRequired) {
            parameters.put(key, new Parameter("array", description, "object",
                    itemsProperties, java.util.Arrays.asList(itemsRequired)));
            return this;
        }

        public Builder required(String... keys) {
            required.addAll(java.util.Arrays.asList(keys));
            return this;
        }

        public ToolSchema build() {
            return new ToolSchema(name, description, parameters, required);
        }
    }
}
