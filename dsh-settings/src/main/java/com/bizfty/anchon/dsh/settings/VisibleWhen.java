package com.bizfty.anchon.dsh.settings;

/**
 * 联动显隐条件（渲染层 DSL，最小版）— 仅同层兄弟键等值显隐。
 *
 * @param key    同层兄弟键（相对当前字段所在对象层的键）
 * @param equals 可见条件：当该兄弟键当前值深等于 equals 时，本字段显示；否则隐藏。
 *               隐藏 ≠ 删除：值保留在草稿/提交中（渲染层 v-if 隐藏，不清值）。
 */
public record VisibleWhen(String key, Object equals) {
}
