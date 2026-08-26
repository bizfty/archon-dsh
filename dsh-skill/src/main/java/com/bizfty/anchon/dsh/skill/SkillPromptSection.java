package com.bizfty.anchon.dsh.skill;

import com.bizfty.anchon.dsh.core.prompt.PromptTemplateRenderer;
import com.bizfty.anchon.dsh.core.prompt.SystemPromptContext;
import com.bizfty.anchon.dsh.core.prompt.SystemPromptSection;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 技能目录段（order 10）— 注入可用技能目录。
 */
@Component
public class SkillPromptSection implements SystemPromptSection {

    private final SkillService skillService;
    private final PromptTemplateRenderer templateRenderer;

    public SkillPromptSection(SkillService skillService, PromptTemplateRenderer templateRenderer) {
        this.skillService = skillService;
        this.templateRenderer = templateRenderer;
    }

    @Override
    public int order() {
        return 10;
    }

    @Override
    public String render(SystemPromptContext context) {
        List<Skill> skills = skillService.list();
        if (skills.isEmpty()) {
            return "";
        }
        String skillsList = skills.stream()
                .map(s -> {
                    StringBuilder sb = new StringBuilder();
                    sb.append("- `").append(s.name()).append('`');
                    if (s.description() != null && !s.description().isBlank()) {
                        sb.append(" — ").append(s.description());
                    }
                    return sb.toString();
                })
                .collect(Collectors.joining("\n"));
        return templateRenderer.render("prompt/skill-directory.txt",
                Map.of("skills_list", skillsList));
    }
}