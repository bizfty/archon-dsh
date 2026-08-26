package com.bizfty.anchon.dsh.agent;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Agent 循环配置（对应 DSH agent-loop 的 Config：maxSteps/maxParallelToolCalls/
 * 默认模型/温度）。
 * <p>
 * max-steps 为单 turn 的防失控兜底（官方 agent-loop 无 per-turn 步数上限，
 * turn 会一直跑到模型不再调用工具或撞 max-tokens；此处保留上限仅防失控循环）。
 */
@Component
public class AgentLoopProperties {

    private final int maxSteps;
    private final double temperature;
    private final int maxHistoryMessages;
    private final int maxParallelToolCalls;

    public AgentLoopProperties(
            @Value("${dsh.agent.max-steps:200}") int maxSteps,
            @Value("${dsh.agent.temperature:0.7}") double temperature,
            @Value("${dsh.agent.max-history-messages:200}") int maxHistoryMessages,
            @Value("${dsh.agent.max-parallel-tool-calls:10}") int maxParallelToolCalls) {
        if (maxSteps <= 0) {
            throw new IllegalArgumentException("dsh.agent.max-steps 必须为正整数");
        }
        this.maxSteps = maxSteps;
        this.temperature = temperature;
        this.maxHistoryMessages = maxHistoryMessages;
        this.maxParallelToolCalls = Math.max(1, maxParallelToolCalls);
    }

    public int maxSteps() {
        return maxSteps;
    }

    public double temperature() {
        return temperature;
    }

    public int maxHistoryMessages() {
        return maxHistoryMessages;
    }

    public int maxParallelToolCalls() {
        return maxParallelToolCalls;
    }
}
