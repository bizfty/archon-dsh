package com.example.dsh.plan;

import com.example.dsh.core.model.SessionId;
import com.example.dsh.core.prompt.SystemPromptContext;
import com.example.dsh.core.prompt.SystemPromptSection;
import org.springframework.stereotype.Component;

/**
 * 计划模式 prompt 段（order 20）— 激活时注入计划内容与约束。
 */
@Component
public class PlanPromptSection implements SystemPromptSection {

    private final PlanModeService planModeService;

    public PlanPromptSection(PlanModeService planModeService) {
        this.planModeService = planModeService;
    }

    @Override
    public int order() {
        return 20;
    }

    @Override
    public String render(SystemPromptContext context) {
        if (context.session() == null) {
            return "";
        }
        SessionId sessionId = context.session().id();
        if (!planModeService.isActive(sessionId)) {
            return "";
        }
        String plan = planModeService.planText(sessionId);
        StringBuilder sb = new StringBuilder();
        sb.append("## 计划模式\n");
        sb.append("当前处于计划模式：**只规划，不实现代码、不改文件**。");
        if (plan != null && !plan.isBlank()) {
            sb.append("当前计划：\n").append(plan);
        }
        sb.append("\n完成规划后调用 exit_plan_mode 提交计划。\n");
        return sb.toString();
    }
}
