package com.example.dsh.tool;

import com.example.dsh.core.prompt.SystemPromptContext;
import com.example.dsh.core.prompt.SystemPromptSection;
import org.springframework.stereotype.Component;

/**
 * 工具指导段（order 100）— 帮助模型理解工具调用约束。
 * <p>
 * 工具 schema 本体随模型请求下发；本段只给使用约定，不重复 schema。
 */
@Component
public class ToolsGuidanceSection implements SystemPromptSection {

    @Override
    public int order() {
        return 100;
    }

    @Override
    public String render(SystemPromptContext context) {
        if (context.toolRefs() == null || context.toolRefs().isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("## 可用工具\n");
        for (com.example.dsh.core.prompt.ToolRef ref : context.toolRefs()) {
            sb.append("- `").append(ref.name()).append("`");
            if (ref.description() != null && !ref.description().isBlank()) {
                sb.append(" — ").append(ref.description());
            }
            sb.append('\n');
        }
        sb.append("\n规则：工具参数必须严格符合 schema；调用失败时根据错误信息修正后重试；"
                + "一次只做一个有意义的动作。\n");
        return sb.toString();
    }
}
