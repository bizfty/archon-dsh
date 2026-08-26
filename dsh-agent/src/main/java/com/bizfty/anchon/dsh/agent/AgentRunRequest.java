package com.bizfty.anchon.dsh.agent;

import com.bizfty.anchon.dsh.core.model.SessionId;

/**
 * Agent 运行请求 — 一次用户消息驱动的 turn。
 * <p>
 * planStepId 可空：续轮时携带"当前应执行的计划步骤 id"（用于工具调用关联
 * plan_step_execution 表）；无计划/普通聊天时为 null。
 */
public record AgentRunRequest(
        SessionId sessionId,
        String userMessage,
        String modelOverride,
        String apiKeyOverride,
        String agentId,
        String executionId,
        int delegationDepth,
        String planStepId) {

    public static Builder builder() {
        return new Builder();
    }

    /** 复制请求并替换 planStepId（为空则原样返回）。 */
    public AgentRunRequest withPlanStepId(String planStepId) {
        if (planStepId == null || planStepId.isBlank()
                || (this.planStepId != null && this.planStepId.equals(planStepId))) {
            return this;
        }
        return new AgentRunRequest(sessionId, userMessage, modelOverride, apiKeyOverride,
                agentId, executionId, delegationDepth, planStepId);
    }

    public static final class Builder {
        private SessionId sessionId;
        private String userMessage;
        private String modelOverride;
        private String apiKeyOverride;
        private String agentId;
        private String executionId;
        private int delegationDepth;
        private String planStepId;

        public Builder sessionId(SessionId sessionId) {
            this.sessionId = sessionId;
            return this;
        }

        public Builder userMessage(String userMessage) {
            this.userMessage = userMessage;
            return this;
        }

        public Builder modelOverride(String modelOverride) {
            this.modelOverride = modelOverride;
            return this;
        }

        public Builder apiKeyOverride(String apiKeyOverride) {
            this.apiKeyOverride = apiKeyOverride;
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

        public Builder delegationDepth(int delegationDepth) {
            this.delegationDepth = delegationDepth;
            return this;
        }

        public Builder planStepId(String planStepId) {
            this.planStepId = planStepId;
            return this;
        }

        public AgentRunRequest build() {
            return new AgentRunRequest(sessionId, userMessage, modelOverride, apiKeyOverride,
                    agentId, executionId, delegationDepth, planStepId);
        }
    }
}
