package com.bizfty.anchon.dsh.plantask;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 计划任务服务 — 提交 / 查询 / 领取 / 状态流转 / 心跳 / 重跑 / 重启恢复。
 * <p>
 * 领取语义：CAS（读取 → 校验 queued → 写入 claimed）。因统一 KV 无行级原子 CAS，
 * 依赖「每工作区单 Worker（进程内）+ 乐观 revision 校验」避免并发重复领取：
 * 读回后校验 revision 未变才提交新状态；若已被他者领取则返回空。
 */
@Service
public class TaskService {

    private static final Logger log = LoggerFactory.getLogger(TaskService.class);

    private final TaskStore store;
    private final long staleThresholdMs;
    private final long lostThresholdMs;

    public TaskService(TaskStore store,
                       @Value("${dsh.plantask.worker.stale-threshold-ms:60000}") long staleThresholdMs,
                       @Value("${dsh.plantask.worker.lost-threshold-ms:180000}") long lostThresholdMs) {
        this.store = store;
        this.staleThresholdMs = Math.max(1000, staleThresholdMs);
        this.lostThresholdMs = Math.max(this.staleThresholdMs + 1000, lostThresholdMs);
    }

    /** 批量提交多个任务，全部入队（queued），返回创建的任务。 */
    public List<PlanTask> submit(String workspaceId, List<String> prompts) {
        if (workspaceId == null || workspaceId.isBlank()) {
            throw new IllegalArgumentException("workspaceId 不能为空");
        }
        if (prompts == null || prompts.isEmpty()) {
            throw new IllegalArgumentException("至少提交一个任务");
        }
        long base = System.currentTimeMillis();
        List<PlanTask> created = new ArrayList<>();
        int seq = 0;
        for (String raw : prompts) {
            String prompt = raw == null ? "" : raw.trim();
            if (prompt.isEmpty()) {
                continue;
            }
            // 同一批内递增 createdAt，保证列表 FIFO 稳定（同毫秒内不依赖存储迭代顺序）
            long now = base + seq++;
            PlanTask task = new PlanTask(TaskStore.newId(), workspaceId, prompt,
                    PlanTask.STATUS_QUEUED, null, null, now, 0, 0, null, null, 1,
                    0, 0, null, 1);
            store.save(workspaceId, task);
            created.add(task);
        }
        if (created.isEmpty()) {
            throw new IllegalArgumentException("没有有效任务内容");
        }
        log.info("[PlanTask] 工作区 {} 批量提交 {} 个任务", workspaceId, created.size());
        return created;
    }

    /** 按工作区列出任务池。 */
    public List<PlanTask> list(String workspaceId) {
        return store.list(workspaceId);
    }

    public Optional<PlanTask> get(String workspaceId, String taskId) {
        return store.get(workspaceId, taskId);
    }

    /**
     * 领取下一个 queued 任务（CAS）。成功返回任务并置为 claimed；
     * 无 queued 任务或已被领取返回 empty。
     */
    public Optional<PlanTask> claimNext(String workspaceId, String workerId) {
        for (PlanTask candidate : store.list(workspaceId)) {
            if (!PlanTask.STATUS_QUEUED.equals(candidate.status())) {
                continue;
            }
            long now = System.currentTimeMillis();
            var claimed = transition(workspaceId, candidate, PlanTask.STATUS_QUEUED,
                    cur -> new PlanTask(candidate.id(), candidate.workspaceId(), candidate.prompt(),
                            PlanTask.STATUS_CLAIMED, workerId, candidate.executorSessionId(),
                            candidate.createdAt(), now, 0,
                            candidate.result(), candidate.error(), candidate.attempt(),
                            now, now, "已认领，待启动",
                            candidate.revision() + 1));
            if (claimed.isPresent()) {
                log.info("[PlanTask] 任务 {} 由 Worker {} 领取", candidate.id(), workerId);
                return claimed;
            }
        }
        return Optional.empty();
    }

    /**
     * 通用状态流转：读回最新 → 校验期望状态 → 应用转移函数 → 保存。
     * revision 乐观校验防止覆盖他者并发写入。
     */
    private Optional<PlanTask> transition(String workspaceId, PlanTask expected,
                                          String expectedStatus,
                                          java.util.function.Function<PlanTask, PlanTask> apply) {
        PlanTask latest = store.get(workspaceId, expected.id()).orElse(null);
        if (latest == null || !expectedStatus.equals(latest.status())
                || latest.revision() != expected.revision()) {
            return Optional.empty();
        }
        PlanTask updated = apply.apply(latest);
        store.save(workspaceId, updated);
        return Optional.of(updated);
    }

    /** 任务执行状态流转（由 Worker/执行器调用）。 */
    public Optional<PlanTask> updateStatus(String workspaceId, String taskId, String fromStatus,
                                           String toStatus, String claimedBy, String executorSessionId,
                                           String result, String error) {
        PlanTask current = store.get(workspaceId, taskId).orElse(null);
        if (current == null || !fromStatus.equals(current.status())) {
            return Optional.empty();
        }
        long now = System.currentTimeMillis();
        boolean terminal = PlanTask.STATUS_DONE.equals(toStatus)
                || PlanTask.STATUS_FAILED.equals(toStatus)
                || PlanTask.STATUS_KILLED.equals(toStatus);
        PlanTask updated = new PlanTask(
                current.id(), current.workspaceId(), current.prompt(), toStatus,
                claimedBy != null ? claimedBy : current.claimedBy(),
                executorSessionId != null ? executorSessionId : current.executorSessionId(),
                current.createdAt(), current.claimedAt() != 0 ? current.claimedAt() : now,
                terminal ? now : current.finishedAt(),
                result != null ? result : current.result(),
                error != null ? error : current.error(),
                current.attempt(),
                now, now,
                terminal ? (error != null ? "已失败" : "已完成") : current.activity(),
                current.revision() + 1);
        store.save(workspaceId, updated);
        log.info("[PlanTask] 任务 {} 状态 {} → {}", taskId, fromStatus, toStatus);
        return Optional.of(updated);
    }

    /** 为 running 任务绑定执行承载会话 id（不改变状态，供任务级提问关联）。 */
    public Optional<PlanTask> bindExecutorSession(String workspaceId, String taskId, String sessionId) {
        PlanTask current = store.get(workspaceId, taskId).orElse(null);
        if (current == null || sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        PlanTask updated = new PlanTask(
                current.id(), current.workspaceId(), current.prompt(), current.status(),
                current.claimedBy(), sessionId, current.createdAt(), current.claimedAt(),
                current.finishedAt(), current.result(), current.error(),
                current.attempt(), current.lastHeartbeatAt(), current.lastActivityAt(), current.activity(),
                current.revision() + 1);
        store.save(workspaceId, updated);
        return Optional.of(updated);
    }

    /**
     * 心跳：刷新 running/claimed/waiting_question 任务的 lastHeartbeatAt（不改变状态）。
     * 由执行器在任务执行期间周期性调用，用于判定任务是否仍在运行。
     */
    public Optional<PlanTask> heartbeat(String workspaceId, String taskId) {
        PlanTask current = store.get(workspaceId, taskId).orElse(null);
        if (current == null || !current.active()) {
            return Optional.empty();
        }
        long now = System.currentTimeMillis();
        PlanTask updated = new PlanTask(
                current.id(), current.workspaceId(), current.prompt(), current.status(),
                current.claimedBy(), current.executorSessionId(), current.createdAt(), current.claimedAt(),
                current.finishedAt(), current.result(), current.error(),
                current.attempt(), now, current.lastActivityAt(), current.activity(),
                current.revision() + 1);
        store.save(workspaceId, updated);
        return Optional.of(updated);
    }

    /**
     * 上报当前动作摘要（进度 L1），同时刷新 lastActivityAt 与 lastHeartbeatAt。
     */
    public Optional<PlanTask> reportActivity(String workspaceId, String taskId, String activity) {
        PlanTask current = store.get(workspaceId, taskId).orElse(null);
        if (current == null || !current.active()) {
            return Optional.empty();
        }
        long now = System.currentTimeMillis();
        PlanTask updated = new PlanTask(
                current.id(), current.workspaceId(), current.prompt(), current.status(),
                current.claimedBy(), current.executorSessionId(), current.createdAt(), current.claimedAt(),
                current.finishedAt(), current.result(), current.error(),
                current.attempt(), now, now,
                activity != null ? activity : current.activity(),
                current.revision() + 1);
        store.save(workspaceId, updated);
        return Optional.of(updated);
    }

    /** 重跑：终态/失败任务回 queued，attempt+1。 */
    public Optional<PlanTask> rerun(String workspaceId, String taskId) {
        PlanTask current = store.get(workspaceId, taskId).orElse(null);
        if (current == null) {
            return Optional.empty();
        }
        PlanTask updated = new PlanTask(
                current.id(), current.workspaceId(), current.prompt(), PlanTask.STATUS_QUEUED,
                null, null, current.createdAt(), 0, 0, null, null,
                current.attempt() + 1, 0, 0, null, current.revision() + 1);
        store.save(workspaceId, updated);
        log.info("[PlanTask] 任务 {} 重跑（attempt {}）", taskId, updated.attempt());
        return Optional.of(updated);
    }

    /**
     * 重启恢复：进程重启后，此前 claimed/running/waiting_question 的任务无执行者，
     * 回置 queued（attempt 不增）供 Worker 重新认领，避免永久悬挂。
     */
    public int recoverOrphans(String workspaceId) {
        int recovered = 0;
        for (PlanTask t : store.list(workspaceId)) {
            if (t.active()) {
                PlanTask reset = new PlanTask(t.id(), t.workspaceId(), t.prompt(),
                        PlanTask.STATUS_QUEUED, null, null, t.createdAt(), 0, 0,
                        null, "已因重启重置，待重新认领", t.attempt(),
                        0, 0, null, t.revision() + 1);
                store.save(workspaceId, reset);
                recovered++;
            }
        }
        if (recovered > 0) {
            log.info("[PlanTask] 工作区 {} 重启恢复 {} 个孤儿任务 → queued", workspaceId, recovered);
        }
        return recovered;
    }

    /**
     * 手动回收单个失联/疑似卡死任务 → queued（不改变 attempt）。
     * 仅当任务处于活跃态且存活判定为 stale/lost 时允许，避免误伤真运行任务。
     */
    public Optional<PlanTask> recover(String workspaceId, String taskId, int inFlight) {
        PlanTask current = store.get(workspaceId, taskId).orElse(null);
        if (current == null || !current.active()) {
            return Optional.empty();
        }
        TaskRuntime rt = runtime(workspaceId, taskId, inFlight).orElse(null);
        if (rt != null && TaskRuntime.LIVENESS_ALIVE.equals(rt.liveness())) {
            log.warn("[PlanTask] 任务 {} 仍存活（alive），拒绝回收", taskId);
            return Optional.empty();
        }
        PlanTask reset = new PlanTask(current.id(), current.workspaceId(), current.prompt(),
                PlanTask.STATUS_QUEUED, null, null, current.createdAt(), 0, 0,
                null, "已手动回收，待重新认领", current.attempt(),
                0, 0, null, current.revision() + 1);
        store.save(workspaceId, reset);
        log.info("[PlanTask] 任务 {} 手动回收 → queued", taskId);
        return Optional.of(reset);
    }

    /** 计算单任务运行态。 */
    public Optional<TaskRuntime> runtime(String workspaceId, String taskId, int inFlight) {
        return store.get(workspaceId, taskId)
                .map(t -> TaskRuntime.of(t, System.currentTimeMillis(),
                        staleThresholdMs, lostThresholdMs, inFlight));
    }

    public long staleThresholdMs() {
        return staleThresholdMs;
    }

    public long lostThresholdMs() {
        return lostThresholdMs;
    }
}
