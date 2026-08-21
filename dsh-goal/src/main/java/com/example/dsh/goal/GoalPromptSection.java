package com.example.dsh.goal;

import com.example.dsh.core.prompt.SystemPromptContext;
import com.example.dsh.core.prompt.SystemPromptSection;
import org.springframework.stereotype.Component;

/**
 * 目标 prompt 段 — 会话存在当前目标时注入 system prompt（对应 DSH goal 的
 * 目标感知 prompt）。无目标时返回空串不贡献内容。
 */
@Component
public class GoalPromptSection implements SystemPromptSection {

    private final GoalService goals;

    public GoalPromptSection(GoalService goals) {
        this.goals = goals;
    }

    @Override
    public int order() {
        return 3; // persona 附近，早于技能/计划注入
    }

    @Override
    public String render(SystemPromptContext context) {
        if (context.session() == null) {
            return "";
        }
        return goals.current(context.session().id().value())
                .map(g -> {
                    StringBuilder sb = new StringBuilder();
                    sb.append("当前目标: ").append(g.objective()).append('\n');
                    sb.append("（phase=").append(g.phase())
                            .append(", rounds=").append(g.roundsStarted()).append('/')
                            .append(g.maxGoalRounds()).append(", id=").append(g.id())
                            .append(", revision=").append(g.revision()).append(')');
                    if (g.blockedReason() != null) {
                        sb.append("\n阻塞: ").append(g.blockedCode()).append(" — ").append(g.blockedReason());
                    }
                    return sb.append('\n').toString();
                })
                .orElse("");
    }
}
