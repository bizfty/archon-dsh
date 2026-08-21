package com.example.dsh.code;

import com.example.dsh.core.prompt.SystemPromptContext;
import com.example.dsh.core.prompt.SystemPromptSection;
import org.springframework.stereotype.Component;

/**
 * Code Mode 指导段（order 150）— 说明 run_code 的编程契约（对应 DSH tools:sdk 段的轻量版）。
 */
@Component
public class CodeModePromptSection implements SystemPromptSection {

    @Override
    public int order() {
        return 150;
    }

    @Override
    public String render(SystemPromptContext context) {
        if (context.toolRefs() == null || context.toolRefs().isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("## 编写 run_code 程序\n");
        sb.append("run_code 接受两个参数：code（async 程序体）与 description。程序内：\n");
        sb.append("- 以 `await tools.工具名(参数)` 调用工具（参数必须是 JSON 对象）。\n");
        sb.append("- 失败的调用会 reject，用 try/catch 处理并继续。\n");
        sb.append("- 用 `return` 返回结果，`console.log` 输出日志（只有打印与返回的内容会回到对话）。\n");
        sb.append("- 程序状态每次全新，不跨调用保留。\n");
        sb.append("可用工具：\n");
        for (com.example.dsh.core.prompt.ToolRef ref : context.toolRefs()) {
            sb.append("- `").append(ref.name()).append("`");
            if (ref.description() != null && !ref.description().isBlank()) {
                sb.append(" — ").append(ref.description());
            }
            sb.append('\n');
        }
        sb.append('\n');
        return sb.toString();
    }
}
