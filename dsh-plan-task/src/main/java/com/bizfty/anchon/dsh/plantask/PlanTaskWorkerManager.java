package com.bizfty.anchon.dsh.plantask;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 计划任务 Worker 管理 — 每工作区一个常驻认领循环（对应 DSH plan-task worker/claim）。
 * <p>
 * 空闲认领语义：对某工作区，当前并发执行数低于上限时，自动认领下一个 queued 任务，
 * 领取成功后派发到虚拟线程执行（互不阻塞）；执行结束回写 done/failed。
 * 领取 CAS 由 {@link TaskService#claimNext} 保证（每工作区单调度循环 + revision 校验）。
 * <p>
 * 心跳：任务执行期间启动周期心跳线程刷新 lastHeartbeatAt，执行结束（含异常）取消；
 * 配合 {@link TaskRuntime} 可判定任务 alive/stale/lost。
 * <p>
 * 触发方式：{@link #pump(String)} 可被外部（定时调度 / 提交后 / REST tick）调用；
 * 也可由 {@link #scheduleLoop()} 启动后台轮询。默认以调度循环运行（间隔可配）。
 */
@Component
public class PlanTaskWorkerManager {

    private static final Logger log = LoggerFactory.getLogger(PlanTaskWorkerManager.class);

    private final TaskService taskService;
    private final PlanTaskExecutor executor;
    private final int concurrency;
    private final long pollIntervalMs;
    private final long heartbeatIntervalMs;
    private final boolean autoSchedule;

    /** workspaceId → 当前并发执行计数。 */
    private final Map<String, AtomicInteger> inFlight = new ConcurrentHashMap<>();
    /** workspaceId → 正在执行的 taskId 集合。 */
    private final Map<String, Set<String>> runningTasks = new ConcurrentHashMap<>();
    /** 已登记的工作区。 */
    private final Set<String> workspaces = ConcurrentHashMap.newKeySet();
    private volatile boolean loopStarted = false;

    public PlanTaskWorkerManager(TaskService taskService,
                                 PlanTaskExecutor executor,
                                 @Value("${dsh.plantask.worker.concurrency:4}") int concurrency,
                                 @Value("${dsh.plantask.worker.poll-interval-ms:1000}") long pollIntervalMs,
                                 @Value("${dsh.plantask.worker.heartbeat-interval-ms:10000}") long heartbeatIntervalMs,
                                 @Value("${dsh.plantask.worker.auto-schedule:true}") boolean autoSchedule) {
        this.taskService = taskService;
        this.executor = executor;
        this.concurrency = Math.max(1, concurrency);
        this.pollIntervalMs = Math.max(200, pollIntervalMs);
        this.heartbeatIntervalMs = Math.max(1000, heartbeatIntervalMs);
        this.autoSchedule = autoSchedule;
    }

    /** 登记某工作区为活跃（提交任务 / 启动扫描时调用）。 */
    public void register(String workspaceId) {
        if (workspaceId != null && !workspaceId.isBlank()) {
            workspaces.add(workspaceId);
            inFlight.computeIfAbsent(workspaceId, k -> new AtomicInteger());
            runningTasks.computeIfAbsent(workspaceId, k -> ConcurrentHashMap.newKeySet());
        }
    }

    /** 启动后台调度循环（幂等）。 */
    public synchronized void scheduleLoop() {
        if (loopStarted) {
            return;
        }
        loopStarted = true;
        Thread loop = Thread.ofVirtual().name("plan-task-worker-loop").start(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    for (String ws : workspaces) {
                        try {
                            pump(ws);
                        } catch (Exception e) {
                            log.warn("[PlanTask] 工作区 {} 轮询异常: {}", ws, e.getMessage());
                        }
                    }
                    Thread.sleep(pollIntervalMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
        if (!autoSchedule) {
            log.info("[PlanTask] Worker 自动调度未启用，由外部触发 pump");
        }
        log.info("[PlanTask] Worker 调度循环已启动（并发上限 {}，心跳间隔 {}ms）",
                concurrency, heartbeatIntervalMs);
    }

    /**
     * 认领并执行一次：在工作区并发未满时领取下一个 queued 任务并派发到虚拟线程。
     * 返回本次新领取并派发的任务数。
     */
    public int pump(String workspaceId) {
        AtomicInteger count = inFlight.computeIfAbsent(workspaceId, k -> new AtomicInteger());
        int started = 0;
        while (count.get() < concurrency) {
            var claimed = taskService.claimNext(workspaceId, "worker:" + workspaceId);
            if (claimed.isEmpty()) {
                break;
            }
            PlanTask task = claimed.get();
            // claimed → running
            taskService.updateStatus(workspaceId, task.id(), PlanTask.STATUS_CLAIMED,
                    PlanTask.STATUS_RUNNING, task.claimedBy(), null, null, null);
            count.incrementAndGet();
            runningTasks.computeIfAbsent(workspaceId, k -> ConcurrentHashMap.newKeySet()).add(task.id());
            started++;
            Thread.ofVirtual().name("plan-task-" + task.id()).start(() -> dispatch(workspaceId, task.id()));
        }
        return started;
    }

    /** 在虚拟线程中执行已 running 的任务，结束后回写终态并释放并发槽位。 */
    private void dispatch(String workspaceId, String taskId) {
        AtomicInteger count = inFlight.computeIfAbsent(workspaceId, k -> new AtomicInteger());
        Set<String> running = runningTasks.computeIfAbsent(workspaceId, k -> ConcurrentHashMap.newKeySet());
        Thread heartbeat = startHeartbeat(workspaceId, taskId);
        try {
            PlanTask task = taskService.get(workspaceId, taskId).orElse(null);
            if (task == null) {
                return;
            }
            PlanTaskExecutor.ExecutionOutcome outcome = executor.execute(workspaceId, taskId, task.prompt());
            taskService.updateStatus(workspaceId, taskId, PlanTask.STATUS_RUNNING,
                    outcome.status(), null, null, outcome.result(), outcome.error());
        } catch (Throwable e) {
            log.error("[PlanTask] 任务 {} 执行异常", taskId, e);
            taskService.updateStatus(workspaceId, taskId, PlanTask.STATUS_RUNNING,
                    PlanTask.STATUS_FAILED, null, null, null,
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        } finally {
            if (heartbeat != null) {
                heartbeat.interrupt();
            }
            running.remove(taskId);
            count.decrementAndGet();
        }
    }

    /** 启动周期心跳线程（虚拟线程），执行结束由调用方 interrupt 取消。 */
    private Thread startHeartbeat(String workspaceId, String taskId) {
        try {
            return Thread.ofVirtual().name("plan-task-hb-" + taskId).start(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        Thread.sleep(heartbeatIntervalMs);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    try {
                        taskService.heartbeat(workspaceId, taskId);
                    } catch (Exception e) {
                        log.debug("[PlanTask] 任务 {} 心跳失败: {}", taskId, e.getMessage());
                    }
                }
            });
        } catch (Exception e) {
            log.debug("[PlanTask] 任务 {} 心跳线程启动失败: {}", taskId, e.getMessage());
            return null;
        }
    }

    /** 当前并发执行数（诊断）。 */
    public int inFlight(String workspaceId) {
        return inFlight.computeIfAbsent(workspaceId, k -> new AtomicInteger()).get();
    }

    /** 并发上限（诊断）。 */
    public int concurrency() {
        return concurrency;
    }

    /** 当前正在执行的 taskId 集合快照（诊断）。 */
    public Set<String> runningTaskIds(String workspaceId) {
        Set<String> s = runningTasks.get(workspaceId);
        return s == null ? Set.of() : Set.copyOf(s);
    }
}
