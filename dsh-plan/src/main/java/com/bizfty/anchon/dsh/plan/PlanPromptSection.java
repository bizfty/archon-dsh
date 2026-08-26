package com.bizfty.anchon.dsh.plan;

import com.bizfty.anchon.dsh.core.model.SessionId;
import com.bizfty.anchon.dsh.core.prompt.PromptTemplateRenderer;
import com.bizfty.anchon.dsh.core.prompt.SystemPromptContext;
import com.bizfty.anchon.dsh.core.prompt.SystemPromptSection;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 计划模式 prompt 段（order 20）— 激活时注入计划内容与约束。
 */
@Component
public class PlanPromptSection implements SystemPromptSection {

    private final PlanModeService planModeService;
    private final PromptTemplateRenderer templateRenderer;

    public PlanPromptSection(PlanModeService planModeService,
                              PromptTemplateRenderer templateRenderer) {
        this.planModeService = planModeService;
        this.templateRenderer = templateRenderer;
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
        return templateRenderer.render("prompt/plan-mode.txt",
                Map.of("plan_text", plan != null ? plan : ""));
    }
}