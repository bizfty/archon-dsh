package com.example.dsh.tool;

import com.example.dsh.core.model.SessionId;

/**
 * 工具执行上下文 — 每次 agent 调用时携带的 per-request 状态。
 * <p>
 * 对应 DSH ToolExecution 的请求侧视图（callId/name/arguments/agent/parent）。
 *
 * @param delegationDepth 委托深度（子代理链；对应 DSH SessionHeader.delegationDepth）
 * @param sandboxMode     沙箱模式（null 按 WORKSPACE_WRITE 处理）
 */
public record ToolContext(
        SessionId sessionId,
        String agentId,
        String executionId,
        String cwd,
        int delegationDepth,
        SandboxMode sandboxMode) {

    public static Builder builder() {
        return new Builder();
    }

    /** 生效沙箱模式（null → WORKSPACE_WRITE）。 */
    public SandboxMode effectiveSandboxMode() {
        return sandboxMode == null ? SandboxMode.WORKSPACE_WRITE : sandboxMode;
    }

    public static final class Builder {
        private SessionId sessionId;
        private String agentId;
        private String executionId;
        private String cwd;
        private int delegationDepth;
        private SandboxMode sandboxMode;

        public Builder sessionId(SessionId sessionId) {
            this.sessionId = sessionId;
            return this;
        }

        public Builder agentId(String agentId) {
            this.agentId = agentId;
            return this;
        }

        public Builder executionId(String executionId) {
            this.executionId = executionId;
            return this;
        }

        public Builder cwd(String cwd) {
            this.cwd = cwd;
            return this;
        }

        public Builder delegationDepth(int delegationDepth) {
            this.delegationDepth = delegationDepth;
            return this;
        }

        public Builder sandboxMode(SandboxMode sandboxMode) {
            this.sandboxMode = sandboxMode;
            return this;
        }

        public ToolContext build() {
            return new ToolContext(sessionId, agentId, executionId, cwd, delegationDepth, sandboxMode);
        }
    }
}
