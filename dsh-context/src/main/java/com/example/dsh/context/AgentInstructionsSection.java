package com.example.dsh.context;

import com.example.dsh.core.prompt.SystemPromptContext;
import com.example.dsh.core.prompt.SystemPromptSection;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 工作区指令段（order -20）— 注入 AGENTS.md/CLAUDE.md 内容（对应 DSH context/agent-instructions 的注入面）。
 */
@Component
public class AgentInstructionsSection implements SystemPromptSection {

    private final AgentInstructionsService service;

    public AgentInstructionsSection(AgentInstructionsService service) {
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
        StringBuilder sb = new StringBuilder();
        sb.append("## 工作区指令\n");
        for (AgentInstructionsService.Instruction instruction : instructions) {
            sb.append(instruction.content());
            if (!instruction.content().endsWith("\n")) {
                sb.append('\n');
            }
            sb.append('\n');
        }
        return sb.toString();
    }
}
