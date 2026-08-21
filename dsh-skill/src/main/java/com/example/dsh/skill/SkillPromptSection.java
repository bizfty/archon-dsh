package com.example.dsh.skill;

import com.example.dsh.core.prompt.SystemPromptContext;
import com.example.dsh.core.prompt.SystemPromptSection;
import org.springframework.stereotype.Component;

/**
 * 技能目录段（order 10）— 只注入 name+description 目录（对应 DSH skill 目录语义：
 * body 不缓存，每次由 skill 工具现读）。
 */
@Component
public class SkillPromptSection implements SystemPromptSection {

    private final SkillService skillService;

    public SkillPromptSection(SkillService skillService) {
        this.skillService = skillService;
    }

    @Override
    public int order() {
        return 10;
    }

    @Override
    public String render(SystemPromptContext context) {
        java.util.List<Skill> skills = skillService.list();
        if (skills.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("## 可用技能\n");
        sb.append("当任务匹配技能描述时，先调用 skill 工具加载其正文再执行：\n");
        for (Skill skill : skills) {
            sb.append("- `").append(skill.name()).append('`');
            if (skill.description() != null && !skill.description().isBlank()) {
                sb.append(" — ").append(skill.description());
            }
            sb.append('\n');
        }
        sb.append('\n');
        return sb.toString();
    }
}
