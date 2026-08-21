package com.example.dsh.tool;

import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具注册表 — 注解驱动自注册（@Tool）。
 * <p>
 * 对应 DSH core/tools 的注册面：全局层注册 + 按名查找 + Schema 导出。
 * （agent 作用域遮蔽/restrict 属 P1，届时在 AgentScope 上实例化本类副本。）
 */
@Component
public class ToolRegistry {

    private final Map<String, AgentTool> tools = new LinkedHashMap<>();

    public ToolRegistry(ApplicationContext applicationContext) {
        Map<String, AgentTool> beans = applicationContext.getBeansOfType(AgentTool.class);
        for (AgentTool tool : beans.values()) {
            register(tool);
        }
    }

    private void register(AgentTool tool) {
        Tool annotation = tool.getClass().getAnnotation(Tool.class);
        String name = annotation != null && !annotation.name().isBlank() ? annotation.name() : tool.name();
        if (name == null || name.isBlank()) {
            name = tool.getClass().getSimpleName().toLowerCase();
        }
        if (tools.containsKey(name)) {
            throw new IllegalStateException("重复的工具名: " + name + " (" + tool.getClass().getName() + ")");
        }
        tools.put(name, tool);
    }

    /** 运行时注册（MCP 等动态来源；重名 fail loud）。 */
    public void registerDynamic(AgentTool tool) {
        register(tool);
    }

    public AgentTool getTool(String name) {
        AgentTool tool = tools.get(name);
        if (tool == null) {
            throw new IllegalArgumentException("工具不存在: " + name);
        }
        return tool;
    }

    public boolean hasTool(String name) {
        return tools.containsKey(name);
    }

    public List<AgentTool> allTools() {
        return List.copyOf(tools.values());
    }

    public List<String> toolNames() {
        return List.copyOf(tools.keySet());
    }

    /** 导出全部工具引用（system-prompt 指导段用）。 */
    public List<com.example.dsh.core.prompt.ToolRef> toToolRefs(com.example.dsh.util.JsonUtils jsonUtils) {
        return tools.values().stream()
                .map(t -> new com.example.dsh.core.prompt.ToolRef(
                        t.name(),
                        t.getSchema().description(),
                        jsonUtils.toJson(t.getSchema().inputSchema())))
                .toList();
    }
}
