package com.example.dsh.skill;

import com.example.dsh.tool.AgentTool;
import com.example.dsh.tool.Tool;
import com.example.dsh.tool.ToolCall;
import com.example.dsh.tool.ToolContext;
import com.example.dsh.tool.ToolResult;
import com.example.dsh.tool.ToolSchema;

import java.util.Map;

/**
 * skill 工具 — 按名加载技能正文（对应 DSH skill/tool-skill 的加载器）。
 */
@Tool(name = "skill", description = "加载一个技能的完整说明（正文）。技能名见系统提示中的可用技能列表。")
public class SkillTool implements AgentTool {

    private final SkillService skillService;

    public SkillTool(SkillService skillService) {
        this.skillService = skillService;
    }

    @Override
    public String name() {
        return "skill";
    }

    @Override
    public ToolSchema getSchema() {
        return ToolSchema.builder()
                .name(name())
                .description("加载技能正文。")
                .addParameter("name", "string", "技能名")
                .required("name")
                .build();
    }

    @Override
    public ToolResult execute(ToolCall call, ToolContext context) {
        String name = call.getString("name");
        if (name == null || name.isBlank()) {
            return ToolResult.failure("缺少必要参数 name");
        }
        Skill skill = skillService.get(name);
        if (skill == null) {
            return ToolResult.failure("技能不存在: " + name + "（可用技能见系统提示目录）");
        }
        return ToolResult.success(skill.body(), Map.of("name", skill.name(), "base_path", skill.basePath()));
    }
}
