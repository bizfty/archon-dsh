package com.example.dsh.tool;

/**
 * 沙箱模式（对应 DSH sandbox 的三档模式词汇）。
 * <p>
 * 由 dsh-sandbox 的 SandboxPolicyService 按会话解析；工具通过
 * ToolContext.sandboxMode 读取（null 按 WORKSPACE_WRITE 处理，向后兼容）。
 */
public enum SandboxMode {
    /** 只读：禁止一切写操作与命令执行。 */
    READ_ONLY,
    /** 工作区写：写操作限制在会话工作区内。 */
    WORKSPACE_WRITE,
    /** 全访问（对应 danger-full-access）。 */
    DANGER_FULL_ACCESS
}
