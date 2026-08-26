package com.bizfty.anchon.dsh.context;

import com.bizfty.anchon.dsh.core.prompt.PromptTemplateRenderer;
import com.bizfty.anchon.dsh.core.prompt.SystemPromptContext;
import com.bizfty.anchon.dsh.core.prompt.SystemPromptSection;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 工作区指令段（order -20）— 注入 AGENTS.md/CLAUDE.md 内容。
 */
@Component
public class AgentInstructionsSection implements SystemPromptSection {

    private final PromptTemplateRenderer templateRenderer;
    private final AgentInstructionsService service;

    public AgentInstructionsSection(PromptTemplateRenderer templateRenderer,
                                    AgentInstructionsService service) {
        this.templateRenderer = templateRenderer;
        this.service = service;
    }

    @Override
    public int order() {
        return -20;
    }

    @Override
    public String render(SystemPromptContext context) {
        String cwd = context.session() != null ? context.session().cwd() : null;
        List<AgentInstructionsService.Instruction> instructions = service.find(cwd);
        if (instructions.isEmpty()) {
            return "";
        }
        String content = instructions.stream()
                .map(AgentInstructionsService.Instruction::content)
                .map(c -> c.endsWith("\n") ? c : c + "\n")
                .collect(Collectors.joining("\n"));
        return templateRenderer.render("prompt/agent-instructions.txt",
                Map.of("instructions", content));
    }
}