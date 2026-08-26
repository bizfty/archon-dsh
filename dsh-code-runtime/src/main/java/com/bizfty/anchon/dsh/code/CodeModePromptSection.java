package com.bizfty.anchon.dsh.code;

import com.bizfty.anchon.dsh.core.prompt.PromptTemplateRenderer;
import com.bizfty.anchon.dsh.core.prompt.SystemPromptContext;
import com.bizfty.anchon.dsh.core.prompt.SystemPromptSection;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * Code Mode 指导段（order 150）— 说明 run_code 的编程契约。
 */
@Component
public class CodeModePromptSection implements SystemPromptSection {

    private final PromptTemplateRenderer templateRenderer;

    public CodeModePromptSection(PromptTemplateRenderer templateRenderer) {
        this.templateRenderer = templateRenderer;
    }

    @Override
    public int order() {
        return 150;
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
        return templateRenderer.render("prompt/code-mode.txt",
                Map.of("tools_list", toolsList));
    }
}