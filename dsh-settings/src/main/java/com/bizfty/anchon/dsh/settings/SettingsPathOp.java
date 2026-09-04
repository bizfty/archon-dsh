package com.bizfty.anchon.dsh.settings;

import java.util.List;

/**
 * 一条 path 寻址的设置编辑（wire 形状：{op:"set"|"unset", path:[...], value?}）。
 * 语义与官方 SettingsPathOp 对齐：set 沿 path 写入（创建中间对象）；unset 移除；
 * 空 path 寻址整个 user 文档（unset → 清空 / set → 整体替换）。
 */
public record SettingsPathOp(String op, List<String> path, Object value) {

    public static final String SET = "set";
    public static final String UNSET = "unset";

    public SettingsPathOp {
        if (!SET.equals(op) && !UNSET.equals(op)) {
            throw new IllegalArgumentException("op 仅支持 set|unset: " + op);
        }
        path = path == null ? List.of() : List.copyOf(path);
    }

    public static SettingsPathOp set(List<String> path, Object value) {
        return new SettingsPathOp(SET, path, value);
    }

    public static SettingsPathOp set(String key, Object value) {
        return new SettingsPathOp(SET, List.of(key), value);
    }

    public static SettingsPathOp unset(List<String> path) {
        return new SettingsPathOp(UNSET, path, null);
    }

    public static SettingsPathOp unset(String key) {
        return new SettingsPathOp(UNSET, List.of(key), null);
    }
}
