package com.bizfty.anchon.dsh.tool;

import com.bizfty.anchon.dsh.core.prompt.PromptTemplateRenderer;
import com.bizfty.anchon.dsh.core.prompt.SystemPromptContext;
import com.bizfty.anchon.dsh.core.prompt.SystemPromptSection;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * 工具指导段（order 100）— 帮助模型理解工具调用约束。
 */
@Component
public class ToolsGuidanceSection implements SystemPromptSection {

    private final PromptTemplateRenderer templateRenderer;

    public ToolsGuidanceSection(PromptTemplateRenderer templateRenderer) {
        this.templateRenderer = templateRenderer;
    }

    @Override
    public int order() {
        return 100;
    }

    @Override
    public String render(SystemPromptContext context) {
        if (context.toolRefs() == null || context.toolRefs().isEmpty()) {
            return "";
        }
        String toolsList = context.toolRefs().stream()
                .map(ref -> {
                    StringBuilder sb = new StringBuilder();
                    sb.append("- `").append(ref.name()).append('`');
                    if (ref.description() != null && !ref.description().isBlank()) {
                        sb.append(" — ").append(ref.description());
                    }
                    return sb.toString();
                })
                .collect(Collectors.joining("\n"));
        return templateRenderer.render("prompt/tools-guidance.txt",
                Map.of("tools_list", toolsList));
    }
}