package com.bizfty.anchon.dsh.agent;

import com.bizfty.anchon.dsh.settings.SettingDescriptor;
import com.bizfty.anchon.dsh.settings.SettingsService;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Agent 命名空间设置 schema 接入 — 把 dsh.agent.* 属性默认值正式收编为 settings 默认层，
 * 并为每个键注册描述符（前端动态表单渲染的唯一事实源）。
 * <p>
 * 数值一致性：registerDefaults 的值与 AgentLoopProperties 现值同源同值 →
 * AgentLoopService.effectiveXxx 读取语义不变（默认值即属性值，行为零漂移）；
 * 用户 PUT 覆盖后 effectiveXxx 读覆盖值（原分层语义）。
 */
@Component
public class AgentSettingsConfig {

    public AgentSettingsConfig(AgentLoopProperties properties, SettingsService settingsService) {
        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("temperature", properties.temperature());
        defaults.put("max-steps", properties.maxSteps());
        defaults.put("max-parallel-tool-calls", properties.maxParallelToolCalls());
        settingsService.registerDefaults("agent", defaults);

        settingsService.registerDescriptor("agent", SettingDescriptor.leaf(
                "temperature", "number", "温度", "模型采样温度（dsh.agent.temperature，settings.agent.temperature 覆盖）",
                properties.temperature(), null, 0.0, 2.0, 0.1));
        settingsService.registerDescriptor("agent", SettingDescriptor.leaf(
                "max-steps", "integer", "最大步数", "单 turn 防失控步数上限（dsh.agent.max-steps）",
                properties.maxSteps(), null, 1.0, 10000.0, 1.0));
        settingsService.registerDescriptor("agent", SettingDescriptor.leaf(
                "max-parallel-tool-calls", "integer", "并行工具上限", "单次返回中可并发执行的安全工具数上限（dsh.agent.max-parallel-tool-calls）",
                properties.maxParallelToolCalls(), null, 1.0, 64.0, 1.0));
    }
}
