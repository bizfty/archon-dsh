package com.bizfty.anchon.dsh.core.prompt;

/**
 * 工具引用 — system-prompt 组装所需的工具元数据（不含执行逻辑）。
 * <p>
 * 由 dsh-tool 模块从 ToolRegistry 导出；schemaJson 为 JSON Schema 字符串。
 */
public record ToolRef(
        String name,
        String description,
        String schemaJson) {
}
