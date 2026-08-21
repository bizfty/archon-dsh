package com.example.dsh.core.model;

/**
 * 消息角色 — 会话日志中的 surface 消息角色。
 * <p>
 * 对应 DSH session 事件溯源中的 surface 消息类别：
 * USER / SYSTEM / ASSISTANT（可携带工具调用）/ TOOL（工具结果）。
 */
public enum MessageRole {
    USER,
    SYSTEM,
    ASSISTANT,
    TOOL
}
