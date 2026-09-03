package com.bizfty.anchon.dsh.settings;

import java.util.List;

/**
 * 设置项描述符（schema 层）— 前端动态表单渲染的唯一事实源。
 *
 * @param key          设置键（与 defaults/overrides 同 key）
 * @param type         string | number | integer | boolean | enum
 * @param label        显示标签（无则前端用 key）
 * @param description  说明（tooltip）
 * @param defaultValue 默认值（注册期现值快照，展示用；实际读取仍走 defaults/覆盖分层）
 * @param options      type=enum 时的可选项
 * @param min          number/integer 下界（可空）
 * @param max          number/integer 上界（可空）
 * @param step         number/integer 步进（可空）
 */
public record SettingDescriptor(
        String key,
        String type,
        String label,
        String description,
        Object defaultValue,
        List<String> options,
        Double min,
        Double max,
        Double step) {
}
