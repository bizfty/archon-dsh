package com.example.dsh.agent;

import com.example.dsh.core.model.SessionId;

/**
 * Agent 运行请求 — 一次用户消息驱动的 turn。
 */
public record AgentRunRequest(
        SessionId sessionId,
        String userMessage,
        String modelOverride,
        String apiKeyOverride,
        String agentId,
        String executionId,
        int delegationDepth) {

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private SessionId sessionId;
        private String userMessage;
        private String modelOverride;
        private String apiKeyOverride;
        private String agentId;
        private String executionId;
        private int delegationDepth;

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

        public AgentRunRequest build() {
            return new AgentRunRequest(sessionId, userMessage, modelOverride, apiKeyOverride,
                    agentId, executionId, delegationDepth);
        }
    }
}
