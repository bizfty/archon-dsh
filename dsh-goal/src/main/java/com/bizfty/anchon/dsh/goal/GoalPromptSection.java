package com.bizfty.anchon.dsh.goal;

import com.bizfty.anchon.dsh.core.prompt.PromptTemplateRenderer;
import com.bizfty.anchon.dsh.core.prompt.SystemPromptContext;
import com.bizfty.anchon.dsh.core.prompt.SystemPromptSection;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 目标 prompt 段 — 会话存在当前目标时注入 system prompt。
 */
@Component
public class GoalPromptSection implements SystemPromptSection {

    private final GoalService goals;
    private final PromptTemplateRenderer templateRenderer;

    public GoalPromptSection(GoalService goals, PromptTemplateRenderer templateRenderer) {
        this.goals = goals;
        this.templateRenderer = templateRenderer;
    }

    @Override
    public int order() {
        return 3;
    }

    @Override
    public String render(SystemPromptContext context) {
        if (context.session() == null) {
            return "";
        }
        return goals.current(context.session().id().value())
                .map(g -> {
                    Map<String, String> vars = new HashMap<>();
                    vars.put("objective", g.objective());
                    vars.put("phase", g.phase());
                    vars.put("rounds_started", String.valueOf(g.roundsStarted()));
                    vars.put("max_rounds", String.valueOf(g.maxGoalRounds()));
                    vars.put("goal_id", g.id());
                    vars.put("revision", String.valueOf(g.revision()));
                    vars.put("blocked_section", g.blockedReason() != null
                            ? "\n阻塞: " + g.blockedCode() + " — " + g.blockedReason()
                            : "");
                    return templateRenderer.render("prompt/goal-current.txt", vars);
                })
                .orElse("");
    }
}