package com.bizfty.anchon.dsh.settings;

/**
 * 设置写入冲突：namespace 的 user 文档自调用方读取（expectedRevision）后已被并发推进。
 * 对应官方 SettingsConflictError（code='SETTINGS_CONFLICT'，携带 expected/actual）。
 */
public class SettingsConflictException extends RuntimeException {

    public static final String CODE = "SETTINGS_CONFLICT";

    private final String namespace;
    private final long expected;
    private final long actual;

    public SettingsConflictException(String namespace, long expected, long actual) {
        super("settings namespace \"" + namespace + "\" changed since it was read"
                + " (expected revision " + expected + ", now " + actual + ")");
        this.namespace = namespace;
        this.expected = expected;
        this.actual = actual;
    }

    public String getNamespace() {
        return namespace;
    }

    public long getExpected() {
        return expected;
    }

    public long getActual() {
        return actual;
    }
}
