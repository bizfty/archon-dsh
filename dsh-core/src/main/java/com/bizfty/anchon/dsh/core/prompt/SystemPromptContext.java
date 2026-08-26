package com.bizfty.anchon.dsh.core.prompt;

import com.bizfty.anchon.dsh.core.model.Agent;
import com.bizfty.anchon.dsh.core.model.Session;

import java.util.List;
import java.util.Map;

/**
 * system-prompt 组装上下文 — 传递给每个 {@link SystemPromptSection}。
 */
public record SystemPromptContext(
        Session session,
        Agent agent,
        List<ToolRef> toolRefs,
        Map<String, String> variables) {

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private Session session;
        private Agent agent;
        private List<ToolRef> toolRefs = List.of();
        private Map<String, String> variables = Map.of();

        public Builder session(Session session) {
            this.session = session;
            return this;
        }

        public Builder agent(Agent agent) {
            this.agent = agent;
            return this;
        }

        public Builder toolRefs(List<ToolRef> toolRefs) {
            this.toolRefs = toolRefs;
            return this;
        }

        public Builder variables(Map<String, String> variables) {
            this.variables = variables;
            return this;
        }

        public SystemPromptContext build() {
            return new SystemPromptContext(session, agent, toolRefs, variables);
        }
    }
}
