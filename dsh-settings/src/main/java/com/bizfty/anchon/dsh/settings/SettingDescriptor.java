package com.bizfty.anchon.dsh.settings;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Objects;

/**
 * 设置项描述符（schema 树）— 前端动态表单渲染的唯一事实源。
 *
 * <p>叶子（string/number/integer/boolean/enum）用 {@link #leaf}；嵌套对象用 {@link #group}
 * （children 递归保序）；数组用 {@link #list}（items 为元素描述符，元素可为标量或 object）；
 * 任一层可用 {@link #withVisible} 附加联动显隐。type 常量见 {@link Types}，wire 上仍是字符串
 * （不引枚举，避免大改与 JSON 序列化特例）。
 *
 * @param key          设置键（与 defaults/overrides 同 key）
 * @param type         string | number | integer | boolean | enum | object | array（见 Types）
 * @param label        显示标签（无则前端用 key）
 * @param description  说明（tooltip）
 * @param defaultValue 默认值（注册期现值快照，展示用；实际读取仍走 defaults/覆盖分层）
 * @param options      type=enum 时的可选项
 * @param min          number/integer 下界（可空）
 * @param max          number/integer 上界（可空）
 * @param step         number/integer 步进（可空）
 * @param children     type=object 时的子描述符（注册顺序保序，递归）
 * @param items        type=array 时的元素描述符（元素可为标量或 object）
 * @param visibleWhen  联动：同层兄弟键值深等于 equals 时本字段可见（可空）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SettingDescriptor(
        String key,
        String type,
        String label,
        String description,
        Object defaultValue,
        List<String> options,
        Double min,
        Double max,
        Double step,
        List<SettingDescriptor> children,
        SettingDescriptor items,
        VisibleWhen visibleWhen) {

    /** type 常量（wire 值即这些字符串；不引枚举以兼容存量序列化/反序列化）。 */
    public static final class Types {
        public static final String STRING = "string";
        public static final String NUMBER = "number";
        public static final String INTEGER = "integer";
        public static final String BOOLEAN = "boolean";
        public static final String ENUM = "enum";
        public static final String OBJECT = "object";
        public static final String ARRAY = "array";

        private Types() {
        }
    }

    /**
     * 标量叶子（完整形态）：type 见 {@link Types}，options/min/max/step 按需传，null 即 JSON 省略。
     */
    public static SettingDescriptor leaf(String key, String type, String label, String description,
                                         Object defaultValue, List<String> options,
                                         Double min, Double max, Double step) {
        return new SettingDescriptor(key, type, label, description, defaultValue, options, min, max, step,
                null, null, null);
    }

    /** 常用叶子：无选项/边界（options/min/max/step 全 null）。 */
    public static SettingDescriptor leaf(String key, String type, String label, String description,
                                         Object defaultValue) {
        return leaf(key, type, label, description, defaultValue, null, null, null, null);
    }

    /** enum 叶子（可选项必填）。 */
    public static SettingDescriptor choice(String key, String label, String description,
                                           Object defaultValue, List<String> options) {
        return leaf(key, Types.ENUM, label, description, defaultValue, options, null, null, null);
    }

    /** 数值叶子：min/max/step（type 传 number 或 integer）。 */
    public static SettingDescriptor number(String key, String type, String label, String description,
                                           Object defaultValue, Double min, Double max, Double step) {
        return leaf(key, type, label, description, defaultValue, null, min, max, step);
    }

    /** object 分组：children 保序递归（type=object）。 */
    public static SettingDescriptor group(String key, String label, String description,
                                          SettingDescriptor... children) {
        return new SettingDescriptor(key, Types.OBJECT, label, description, null, null, null, null, null,
                List.of(children), null, null);
    }

    /** array 列表：items 为元素描述符（标量叶子或 object 均可），type=array。 */
    public static SettingDescriptor list(String key, String label, String description,
                                         SettingDescriptor items) {
        return new SettingDescriptor(key, Types.ARRAY, label, description, null, null, null, null, null,
                null, items, null);
    }

    /** 给任意节点附加联动显隐（返回新实例，原实例不变）。 */
    public static SettingDescriptor withVisible(SettingDescriptor descriptor, String whenKey, Object equals) {
        Objects.requireNonNull(descriptor, "descriptor");
        return new SettingDescriptor(descriptor.key(), descriptor.type(), descriptor.label(),
                descriptor.description(), descriptor.defaultValue(), descriptor.options(),
                descriptor.min(), descriptor.max(), descriptor.step(),
                descriptor.children(), descriptor.items(), new VisibleWhen(whenKey, equals));
    }
}
