package com.bizfty.anchon.dsh.agent;

import com.bizfty.anchon.dsh.core.model.Agent;
import com.bizfty.anchon.dsh.core.prompt.SystemPromptSection;

import java.util.List;
import java.util.function.Predicate;

/**
 * Agent 作用域 — 每次运行的 per-agent 组合面（对应 DSH scope：agent 级注册的
 * sections/工具可见性在本作用域内生效，随运行实例化）。
 * <p>
 * 默认按 {@link Agent} 配置（enabled/disabledTools）推导工具可见性；
 * extraSections 是扩展点（preset/子代理可注入该 agent 专属的 prompt 段）。
 */
public final class AgentScope {

    private final List<SystemPromptSection> extraSections;
    private final Predicate<String> toolVisibility;

    private AgentScope(List<SystemPromptSection> extraSections, Predicate<String> toolVisibility) {
        this.extraSections = extraSections;
        this.toolVisibility = toolVisibility;
    }

    /** 按 Agent 配置创建作用域。 */
    public static AgentScope forAgent(Agent agent) {
        return new AgentScope(List.of(), agent::isToolVisible);
    }

    /** 自定义作用域（测试/扩展）。 */
    public static AgentScope of(List<SystemPromptSection> extraSections, Predicate<String> toolVisibility) {
        return new AgentScope(
                extraSections == null ? List.of() : List.copyOf(extraSections),
                toolVisibility == null ? name -> true : toolVisibility);
    }

    public List<SystemPromptSection> extraSections() {
        return extraSections;
    }

    public Predicate<String> toolVisibility() {
        return toolVisibility;
    }
}
