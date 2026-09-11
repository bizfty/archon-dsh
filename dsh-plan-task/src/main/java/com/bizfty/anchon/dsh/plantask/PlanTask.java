package com.bizfty.anchon.dsh.plantask;

/**
 * 计划任务（PlanTask）— 用户提交的一条待执行意图，按工作区分池，由该工作区 Worker 认领后派生子代理执行。
 * <p>
 * 状态机：queued(入队) → claimed(已认领) → running(执行中)
 *   running → waiting_question(阻塞等待用户应答) → running(恢复)
 *   running → done / failed / killed
 *   done/failed/killed → 重跑 → queued
 * <p>
 * 每次状态变更 revision +1（用于乐观并发/领取 CAS）。
 * <p>
 * 运行态观测字段：
 * <ul>
 *   <li>{@code lastHeartbeatAt} — 执行期周期性刷新，用于判定任务是否仍在运行
 *       （alive/stale/lost，由 {@link TaskRuntime} 计算，不落库）。</li>
 *   <li>{@code lastActivityAt} / {@code activity} — 最后活动时间与当前动作摘要（进度 L1）。</li>
 * </ul>
 */
public record PlanTask(
        String id,
        String workspaceId,
        String prompt,
        String status,
        String claimedBy,
        String executorSessionId,
        long createdAt,
        long claimedAt,
        long finishedAt,
        String result,
        String error,
        int attempt,
        long lastHeartbeatAt,
        long lastActivityAt,
        String activity,
        int revision) {

    // ---- 状态常量 ----
    public static final String STATUS_QUEUED = "queued";
    public static final String STATUS_CLAIMED = "claimed";
    public static final String STATUS_RUNNING = "running";
    public static final String STATUS_WAITING_QUESTION = "waiting_question";
    public static final String STATUS_DONE = "done";
    public static final String STATUS_FAILED = "failed";
    public static final String STATUS_KILLED = "killed";

    public PlanTask {
        if (id == null || id.isBlank() || workspaceId == null || workspaceId.isBlank()) {
            throw new IllegalArgumentException("task id/workspaceId 不能为空");
        }
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("task prompt 不能为空");
        }
    }

    /** 是否为终态。 */
    public boolean terminal() {
        return STATUS_DONE.equals(status) || STATUS_FAILED.equals(status) || STATUS_KILLED.equals(status);
    }

    /** 是否处于活跃（未终结）状态。 */
    public boolean active() {
        return STATUS_CLAIMED.equals(status) || STATUS_RUNNING.equals(status)
                || STATUS_WAITING_QUESTION.equals(status);
    }

    /** 对外视图。 */
    public PlanTaskView view() {
        return new PlanTaskView(id, workspaceId, prompt, status, claimedBy, executorSessionId,
                createdAt, claimedAt, finishedAt, result, error, attempt,
                lastHeartbeatAt, lastActivityAt, activity);
    }

    /** 对外展示视图（不含 revision 内部细节）。 */
    public record PlanTaskView(
            String id,
            String workspaceId,
            String prompt,
            String status,
            String claimedBy,
            String executorSessionId,
            long createdAt,
            long claimedAt,
            long finishedAt,
            String result,
            String error,
            int attempt,
            long lastHeartbeatAt,
            long lastActivityAt,
            String activity) {
    }
}
