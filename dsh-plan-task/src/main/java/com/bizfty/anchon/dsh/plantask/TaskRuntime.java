package com.bizfty.anchon.dsh.plantask;

/**
 * 任务运行态视图（计算字段，不落库）— 供前端/接口判断任务「是否真的在跑」。
 *
 * @param taskId         任务 id
 * @param status         名义状态（queued/running/...）
 * @param liveness       存活判定：alive（运行中）/ stale（疑似卡死）/ lost（失联）/ n/a（非活跃态）
 * @param lastHeartbeatAt 最后心跳时间戳（0 表示无）
 * @param lastActivityAt  最后活动时间戳（0 表示无）
 * @param activity        当前动作摘要（可空）
 * @param elapsedMs       自认领以来的耗时（毫秒；终态为结束-认领）
 * @param inFlight        该工作区当前进程内并发执行数
 * @param heartbeatAgeMs  距最后心跳的毫秒数（-1 表示无心跳）
 */
public record TaskRuntime(
        String taskId,
        String status,
        String liveness,
        long lastHeartbeatAt,
        long lastActivityAt,
        String activity,
        long elapsedMs,
        int inFlight,
        long heartbeatAgeMs) {

    public static final String LIVENESS_ALIVE = "alive";
    public static final String LIVENESS_STALE = "stale";
    public static final String LIVENESS_LOST = "lost";
    public static final String LIVENESS_NA = "n/a";

    /**
     * 依据任务与心跳阈值计算运行态。
     *
     * @param task              任务
     * @param now               当前时间
     * @param staleThresholdMs  心跳超过该阈值判 stale
     * @param lostThresholdMs   心跳超过该阈值判 lost
     * @param inFlight          工作区进程内并发数
     */
    public static TaskRuntime of(PlanTask task, long now, long staleThresholdMs,
                                 long lostThresholdMs, int inFlight) {
        String liveness;
        long heartbeatAge = -1;
        if (task.active()) {
            long hb = task.lastHeartbeatAt();
            if (hb <= 0) {
                // 已认领/运行但从未心跳：以认领时间为基准估算
                hb = task.claimedAt();
            }
            if (hb > 0) {
                heartbeatAge = Math.max(0, now - hb);
                if (heartbeatAge <= staleThresholdMs) {
                    liveness = LIVENESS_ALIVE;
                } else if (heartbeatAge <= lostThresholdMs) {
                    liveness = LIVENESS_STALE;
                } else {
                    liveness = LIVENESS_LOST;
                }
            } else {
                liveness = LIVENESS_STALE;
            }
        } else {
            liveness = LIVENESS_NA;
        }
        long end = task.finishedAt() > 0 ? task.finishedAt() : now;
        long elapsed = task.claimedAt() > 0 ? Math.max(0, end - task.claimedAt()) : 0;
        return new TaskRuntime(task.id(), task.status(), liveness,
                task.lastHeartbeatAt(), task.lastActivityAt(), task.activity(),
                elapsed, inFlight, heartbeatAge);
    }
}
