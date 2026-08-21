package com.example.dsh.goal;

/**
 * 目标（Goal）— 持久化 same-session 目标的完整状态（对应 DSH goal 的 GoalSnapshot）。
 * <p>
 * 生命周期 phase：active / paused / blocked / complete；blocked 时带 code + reason。
 * 每次变更 revision +1（CAS：更新须携带精确的 id + revision）。
 * roundsStarted 为已开始的自动延续轮数（由延续驱动方累加，本模块只持久化）。
 */
public record Goal(
        String id,
        String sessionId,
        String objective,
        String phase,
        String blockedCode,
        String blockedReason,
        int maxGoalRounds,
        int roundsStarted,
        long createdAt,
        long updatedAt,
        int revision) {

    /** 允许的 phase。 */
    public static final String PHASE_ACTIVE = "active";
    public static final String PHASE_PAUSED = "paused";
    public static final String PHASE_BLOCKED = "blocked";
    public static final String PHASE_COMPLETE = "complete";

    public Goal {
        if (id == null || id.isBlank() || sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("goal id/sessionId 不能为空");
        }
        if (objective == null || objective.isBlank()) {
            throw new IllegalArgumentException("objective 不能为空");
        }
        if (maxGoalRounds <= 0) {
            throw new IllegalArgumentException("maxGoalRounds 必须为正: " + maxGoalRounds);
        }
        if (PHASE_BLOCKED.equals(phase) && (blockedCode == null || blockedCode.isBlank()
                || blockedReason == null || blockedReason.isBlank())) {
            throw new IllegalArgumentException("blocked phase 必须带 code 与 reason");
        }
    }

    /** 视图（对外展示，不含内部细节）。 */
    public GoalView view() {
        return new GoalView(id, sessionId, objective, phase, blockedCode, blockedReason,
                maxGoalRounds, roundsStarted, createdAt, updatedAt, revision);
    }

    /** 对外视图。 */
    public record GoalView(String id, String sessionId, String objective, String phase,
                           String blockedCode, String blockedReason, int maxGoalRounds,
                           int roundsStarted, long createdAt, long updatedAt, int revision) {
    }
}
