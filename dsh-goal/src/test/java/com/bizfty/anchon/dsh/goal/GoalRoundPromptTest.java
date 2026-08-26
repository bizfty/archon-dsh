package com.bizfty.anchon.dsh.goal;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Goal Round 提示词渲染测试：对齐官方 renderGoalRoundPrompt 的块结构、
 * JSON 引用的目标文本与 Round 计数。
 */
class GoalRoundPromptTest {

    private Goal goal(int roundsStarted, int maxRounds) {
        return new Goal("goal-1", "s1", "第一行\n第二行", Goal.PHASE_ACTIVE, null, null,
                maxRounds, roundsStarted, Instant.now().toEpochMilli(),
                Instant.now().toEpochMilli(), 1);
    }

    @Test
    void rendersGoalRoundBlockWithJsonQuotedObjective() {
        String prompt = GoalRoundPrompt.render(goal(1, 3), 2);

        assertTrue(prompt.startsWith("<goal_round>\n"), prompt);
        assertTrue(prompt.contains("Objective: \"第一行\\n第二行\""), "目标应以 JSON 引用保留换行: " + prompt);
        assertTrue(prompt.contains("Round: 2/3"), "应包含 Round: 2/3: " + prompt);
        assertTrue(prompt.contains("Continue working toward the objective"), prompt);
        assertTrue(prompt.endsWith("</goal_round>"), prompt);
    }

    @Test
    void renderUsesGoalRoundsStartedAsRoundNumber() {
        assertEquals("Round: 3/5", extractRound(GoalRoundPrompt.render(goal(3, 5), 3)));
    }

    private String extractRound(String prompt) {
        String line = prompt.lines().filter(l -> l.startsWith("Round: ")).findFirst().orElse("");
        return line;
    }
}
