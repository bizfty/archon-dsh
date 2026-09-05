package com.bizfty.anchon.dsh.settings;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 官方 schemastery envelope 翻译器（design-client-vue.md C 档 §5.2）。
 *
 * <p>把 {@link SettingDescriptor} 树翻译成官方 schemastery {@code schema.toJSON()} 的
 * wire 形态 —— {@code { uid, refs }} 引用图（uid 自 0 起、DFS 序；refs 的键为十进制字符串，
 * 节点间的子引用（object→dict 值、array→inner、enum→union list 元素）一律存目标 uid 数字）。
 * 前端收编的 dsh-client-schema-form 用 {@code rehydrateSchema(serialized)} 原样重建活校验器
 * （对应官方 ui-settings 的 schema 消费面），validateDraft / nodeAtPath / hasPath / setPath /
 * deletePath 均在重建后的节点树上工作。
 *
 * <p>语义映射（与官方 schemastery 对齐，validate 语义由前端模型层保证）：
 * <ul>
 *   <li>string / number / integer → schemastery {@code string} / {@code number}；integer 无
 *       独立 resolver，沿用注册时的 {@code step}（max-steps 等注册为 step=1）与 min/max；</li>
 *   <li>boolean → {@code boolean}（nullable 回退 {@code meta.default} 由 resolve 通用路径处理）；</li>
 *   <li>enum → {@code union} + 每选项 {@code const} 节点（validate 拒绝列表外值，渲染端遍历
 *       union.list 提取 select 选项）；</li>
 *   <li>object → {@code object}（dict 保序引用 children）；array → {@code array}（inner 引用 items）；</li>
 *   <li>secret → 节点 {@code meta.role = "secret"}（官方 role 语义，wire 值剥离与 write-only 展示仍走
 *       P3 redact view 的 secrets sidecar）；</li>
 *   <li>label / description → {@code meta.label} / {@code meta.description}（label 为 archon 显示名，
 *       schemastery 无原生 label，保留为自定义 meta 键）；visibleWhen → {@code meta.visibleWhen}；</li>
 *   <li>defaultValue → {@code meta.default}（object/array 恒给空容器，与官方 defineMethod 一致）。</li>
 * </ul>
 *
 * <p>本类只做纯翻译，不触碰 P3 的 value/user/revision/secrets/applies（meta 端点在其旁双发本
 * envelope，P3 REST 读取零回归）。
 */
public final class SchemasteryEnvelope {

    private SchemasteryEnvelope() {
    }

    /**
     * 顶层设置描述符列表 → 单个 object envelope（root uid 0，dict 按注册序收所有顶层键）。
     *
     * @param settings describe(ns) 的顶层描述符（每组一个顶层键，group/list 以 children/items 递归）
     * @return JSON-ready {@code { uid: 0, refs: { "0": rootNode, ... } }}（LinkedHashMap 保序）
     */
    public static Map<String, Object> toEnvelope(List<SettingDescriptor> settings) {
        Translator translator = new Translator();
        int rootUid = translator.allocate();
        if (rootUid != 0) {
            throw new IllegalStateException("root uid must be 0, got " + rootUid);
        }
        Map<String, Object> rootNode = new LinkedHashMap<>();
        rootNode.put("type", "object");
        Map<String, Object> rootMeta = new LinkedHashMap<>();
        rootMeta.put("default", new LinkedHashMap<String, Object>());
        rootNode.put("meta", rootMeta);
        Map<String, Integer> dict = new LinkedHashMap<>();
        for (SettingDescriptor descriptor : settings) {
            dict.put(descriptor.key(), translator.translate(descriptor));
        }
        rootNode.put("dict", dict);
        translator.refs.put("0", rootNode);

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("uid", 0);
        envelope.put("refs", translator.refs);
        return envelope;
    }

    private static final class Translator {
        private final Map<String, Object> refs = new LinkedHashMap<>();
        private int nextUid = 0;

        private int allocate() {
            return nextUid++;
        }

        private int translate(SettingDescriptor descriptor) {
            int uid = allocate();
            Map<String, Object> node = new LinkedHashMap<>();
            String type = descriptor.type();
            String kind = type == null ? SettingDescriptor.Types.STRING : type;
            switch (kind) {
                case SettingDescriptor.Types.OBJECT -> {
                    node.put("type", "object");
                    Map<String, Object> children = new LinkedHashMap<>();
                    if (descriptor.children() != null) {
                        for (SettingDescriptor child : descriptor.children()) {
                            children.put(child.key(), translate(child));
                        }
                    }
                    node.put("dict", children);
                }
                case SettingDescriptor.Types.ARRAY -> {
                    node.put("type", "array");
                    node.put("inner", descriptor.items() == null ? null : translate(descriptor.items()));
                }
                case SettingDescriptor.Types.ENUM -> {
                    node.put("type", "union");
                    List<Integer> list = new ArrayList<>();
                    if (descriptor.options() != null) {
                        for (String option : descriptor.options()) {
                            list.add(translateConst(option));
                        }
                    }
                    node.put("list", list);
                }
                case SettingDescriptor.Types.NUMBER, SettingDescriptor.Types.INTEGER -> {
                    node.put("type", "number");
                }
                default -> {
                    // string / boolean 及其余：schemastery 同名 type
                    node.put("type", kind);
                }
            }
            node.put("meta", buildMeta(descriptor));
            refs.put(Integer.toString(uid), node);
            return uid;
        }

        private int translateConst(String value) {
            int uid = allocate();
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("type", "const");
            node.put("value", value);
            node.put("meta", new LinkedHashMap<String, Object>());
            refs.put(Integer.toString(uid), node);
            return uid;
        }

        private Map<String, Object> buildMeta(SettingDescriptor descriptor) {
            Map<String, Object> meta = new LinkedHashMap<>();
            Object defaultValue = descriptor.defaultValue();
            String type = descriptor.type();
            if (SettingDescriptor.Types.OBJECT.equals(type)
                    || SettingDescriptor.Types.ARRAY.equals(type)) {
                meta.put("default", SettingDescriptor.Types.ARRAY.equals(type)
                        ? new ArrayList<>()
                        : new LinkedHashMap<String, Object>());
            } else if (defaultValue != null) {
                meta.put("default", defaultValue);
            }
            if (descriptor.label() != null) {
                meta.put("label", descriptor.label());
            }
            if (descriptor.description() != null) {
                meta.put("description", descriptor.description());
            }
            if (SettingDescriptor.Types.NUMBER.equals(type) || SettingDescriptor.Types.INTEGER.equals(type)) {
                // integer 无独立 schemastery resolver：step=1 之外给自定义 dshType 标记，
                // 供渲染端还原整型控件精度（validate 由 min/max/step 承担，与注册语义一致）
                if (SettingDescriptor.Types.INTEGER.equals(type)) {
                    meta.put("dshType", "integer");
                }
                if (descriptor.min() != null) {
                    meta.put("min", descriptor.min());
                }
                if (descriptor.max() != null) {
                    meta.put("max", descriptor.max());
                }
                if (descriptor.step() != null) {
                    meta.put("step", descriptor.step());
                }
            }
            if (Boolean.TRUE.equals(descriptor.secret())) {
                meta.put("role", "secret");
            }
            if (descriptor.visibleWhen() != null) {
                Map<String, Object> visible = new LinkedHashMap<>();
                visible.put("key", descriptor.visibleWhen().key());
                visible.put("equals", descriptor.visibleWhen().equals());
                meta.put("visibleWhen", visible);
            }
            return meta;
        }
    }
}
