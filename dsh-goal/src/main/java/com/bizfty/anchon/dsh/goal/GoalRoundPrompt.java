package com.bizfty.anchon.dsh.goal;

import com.bizfty.anchon.dsh.util.JsonUtils;

/**
 * Goal Round 续行提示词渲染（对应官方 DSH goal-round-driver 的 renderGoalRoundPrompt）。
 * <p>
 * 输出与官方保持一致：一个 {@code <goal_round>} 块，含 JSON 引用的目标文本、
 * {@code Round: n/max} 计数与续行指引。作为 USER 消息注入会话历史（source=goal round），
 * 模型据此在同一会话内继续朝目标工作。
 */
public final class GoalRoundPrompt {

    private static final JsonUtils JSON = new JsonUtils();

    private GoalRoundPrompt() {
    }

    /**
     * 渲染完整 goal-round 续行提示词。
     *
     * @param goal  当前 active goal（预留下一轮后的视图）
     * @param round 下一轮正数编号（= goal.roundsStarted()）
     * @return 单块续行提示词文本
     */
    public static String render(Goal goal, int round) {
        return "<goal_round>\n"
                + "Objective: " + JSON.toJson(goal.objective()) + "\n"
                + "Round: " + round + "/" + goal.maxGoalRounds() + "\n\n"
                + "Continue working toward the objective in this same session. Treat the current workspace, "
                + "tool results, and durable session state as authoritative; inspect them instead of assuming "
                + "earlier narration is still current. Make concrete progress and verify the result. Before "
                + "claiming completion, gather evidence that the whole objective is achieved, read the current "
                + "goal, and mark it complete. If work remains, leave the goal active for the next round. Follow "
                + "the configured goal-tool policy before reporting a blocker.\n"
                + "</goal_round>";
    }
}
