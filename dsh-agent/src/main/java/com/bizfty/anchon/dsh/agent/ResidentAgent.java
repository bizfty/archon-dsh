package com.bizfty.anchon.dsh.agent;

import com.bizfty.anchon.dsh.core.model.SessionId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * 每会话一个可恢复执行对象（M4，design-resident-agent.md §3/§4.1）——对应上游常驻
 * {@code ReactLoopAgent} 角色：Inbox FIFO 排队 + 单执行者串行消费 + Phase 状态机。
 * <p>
 * 执行载体：**每会话一个虚拟线程执行者**（D2 定案）：无任务时阻塞在 {@code inbox.take()}（挂起，
 * 虚拟线程空闲成本近零，对齐上游"每会话一个执行者、无请求时挂起"）；请求线程
 * {@link #submit} 后通过返回的 {@link CompletableFuture} 等待（同步门面语义）。
 * 同会话任务天然串行（FIFO = 到达顺序）；跨会话各持对象 → 并行。
 * <p>
 * 取消：{@link #abort()} 置协作取消标志（不中断线程/工具），任务内于安全检查点读取
 * {@link #isAbortRequested()} 自行收敛（流式订阅 cancel / step 间隙，m4-3 接线）；已排队但未开始的
 * 独立 turn **不丢弃**（保证用户消息不丢），仅在取消标志下按序继续或由上层决定。
 */
public class ResidentAgent implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ResidentAgent.class);

    private final SessionId sessionId;
    private final BlockingQueue<Job<?>> inbox = new LinkedBlockingQueue<>();
    private final Thread worker;
    private final Object lock = new Object();
    private final AtomicInteger pending = new AtomicInteger();
    /** Phase 迁移通知（M4-5：registry 挂事件总线发布 AGENT_PHASE；可空）。 */
    private volatile Consumer<ResidentPhase> phaseListener;
    /** 已通知的 phase（非 IDLE 去重：连续同值不重发，IDLE 不发布）。 */
    private volatile ResidentPhase lastNotifiedPhase = null;

    private volatile boolean closed = false;
    private volatile boolean abortRequested = false;
    private ResidentPhase phase = ResidentPhase.IDLE;

    // ---- 身份收编（M4-4，design §4.3）：最近一次执行的稳定身份，供 resume/重启恢复 ----
    private volatile String executionId;
    private volatile String agentId;
    private volatile String seriesId;
    private volatile String headerFingerprint;

    /** 队列中的一项：可带类型结果的任务（label 供日志/后续执行序断言）。 */
    private record Job<T>(String label, Callable<T> call, CompletableFuture<T> future) {
    }

    @SuppressWarnings("rawtypes")
    private static final Job POISON = new Job("poison", null, null);

    ResidentAgent(SessionId sessionId) {
        this.sessionId = sessionId;
        this.worker = Thread.ofVirtual()
                .name("resident-agent-" + sessionId.value())
                .start(this::consumeLoop);
    }

    /** 同会话入队一个任务（FIFO 串行）。返回的 future 在任务完成/异常时结束。 */
    public <T> CompletableFuture<T> submit(String label, Callable<T> task) {
        CompletableFuture<T> future = new CompletableFuture<>();
        if (closed) {
            future.completeExceptionally(new CancelledException(sessionId, label));
            return future;
        }
        pending.incrementAndGet();
        synchronized (lock) {
            if (phase == ResidentPhase.IDLE || phase == ResidentPhase.ABORTED) {
                transition(ResidentPhase.QUEUED);
            }
        }
        inbox.add(new Job<>(label, task, future));
        return future;
    }

    /** 当前 Phase（线程安全）。 */
    public ResidentPhase phase() {
        synchronized (lock) {
            return phase;
        }
    }

    /** 注册 phase 迁移通知（registry 在创建执行者时挂载；可重复调用，覆盖旧值）。 */
    public void setPhaseListener(Consumer<ResidentPhase> phaseListener) {
        this.phaseListener = phaseListener;
    }

    /** 在锁内变更 phase 并触发非 IDLE 迁移通知（仅当值确实变化且非 IDLE）。 */
    private void transition(ResidentPhase next) {
        synchronized (lock) {
            phase = next;
        }
        notifyPhase(next);
    }

    /** 通知 phase 迁移（去重：与上次已通知值相同则跳过；IDLE 不发布——存在即 idle，无需恢复）。 */
    private void notifyPhase(ResidentPhase p) {
        Consumer<ResidentPhase> listener = phaseListener;
        if (listener == null || p == ResidentPhase.IDLE) {
            return;
        }
        if (lastNotifiedPhase == p) {
            return;
        }
        lastNotifiedPhase = p;
        try {
            listener.accept(p);
        } catch (RuntimeException e) {
            // 事件监听失败不影响执行者（同 SessionEventBus 异常隔离约定）
            java.lang.System.getLogger("dsh.agent").log(java.lang.System.Logger.Level.WARNING,
                    "phase 通知失败", e);
        }
    }

    /** 是否已请求取消（协作检查点读取；任务开始时自动清除）。 */
    public boolean isAbortRequested() {
        return abortRequested;
    }

    /**
     * 请求取消当前执行（m4-3 起在任务内安全点收敛）。置位后不自动清除：调用方（abort API）
     * 显式 {@link #resetAbort()} 或下一个任务开始时清除。
     */
    public void abort() {
        abortRequested = true;
        synchronized (lock) {
            if (pending.get() == 0) {
                transition(ResidentPhase.ABORTED);
            }
        }
    }

    /** 清除取消标志（新 turn / resume 时调用，保证新执行从干净状态开始）。 */
    public void resetAbort() {
        abortRequested = false;
    }

    /** 会话 id。 */
    public SessionId sessionId() {
        return sessionId;
    }

    /**
     * 收编执行身份（M4-4）：每次 turn（executeInner）确定 executionId/series 后由门面记录。
     * 线程安全（volatile 写）。abort 后该状态即「可 resume 身份」。
     */
    public void recordIdentity(String executionId, String agentId, String seriesId, String headerFingerprint) {
        this.executionId = executionId;
        this.agentId = agentId;
        this.seriesId = seriesId;
        this.headerFingerprint = headerFingerprint;
    }

    /** 最近一次执行的身份（未执行过 → 空串字段）。 */
    public ResidentState residentState() {
        return new ResidentState(executionId == null ? "" : executionId,
                agentId == null ? "" : agentId,
                seriesId == null ? "" : seriesId,
                headerFingerprint == null ? "" : headerFingerprint);
    }

    /** 可 resume 身份：phase=aborted 且已记录 executionId → 返回身份；否则空。 */
    public java.util.Optional<ResidentState> abortedResumeState() {
        ResidentPhase p = phase();
        ResidentState st = residentState();
        if (p == ResidentPhase.ABORTED && !st.executionId().isBlank()) {
            return java.util.Optional.of(st);
        }
        return java.util.Optional.empty();
    }

    /** 稳定执行身份（executionId/agentId/seriesId/headerFingerprint）。 */
    public record ResidentState(String executionId, String agentId, String seriesId, String headerFingerprint) {
    }

    /**
     * 关闭执行者并清空队列：排队未执行任务以 Cancelled 结束；**不中断运行中的任务**
     * （协作式，工具副作用不可中断——同 SessionCancellation 约定），当前任务自然结束后
     * worker 消费到毒丸退出。仅 registry.release/会话删除时调用。
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        Job<?> job;
        while ((job = inbox.poll()) != null) {
            job.future().completeExceptionally(new CancelledException(sessionId, job.label()));
        }
        inbox.add(POISON); // 唤醒/收尾 worker
    }

    @SuppressWarnings("unchecked")
    private void consumeLoop() {
        while (true) {
            Job<?> job;
            try {
                job = inbox.take();
            } catch (InterruptedException e) {
                if (closed) {
                    drainAndReturn();
                    return;
                }
                continue;
            }
            if (job == POISON) {
                return;
            }
            if (closed) {
                if (job.future() != null) {
                    job.future().completeExceptionally(new CancelledException(sessionId, job.label()));
                }
                drainAndReturn();
                return;
            }
            runJob(job);
        }
    }

    @SuppressWarnings("unchecked")
    private void drainAndReturn() {
        Job<?> job;
        while ((job = inbox.poll()) != null && job != POISON) {
            if (job.future() != null) {
                job.future().completeExceptionally(new CancelledException(sessionId, job.label()));
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void runJob(Job<?> job) {
        boolean failed = false;
        boolean cancelled = false;
        resetAbort(); // 新任务从干净状态开始（上一任务的取消残留不传染下一个排队任务——不丢弃用户消息）
        synchronized (lock) {
            if (phase == ResidentPhase.ERROR || phase == ResidentPhase.ABORTED) {
                transition(ResidentPhase.QUEUED); // 新任务自愈：从 error/aborted 回到执行态
            }
            transition(ResidentPhase.RUNNING);
        }
        try {
            Object result = job.call().call();
            ((CompletableFuture<Object>) job.future()).complete(result);
        } catch (Throwable t) {
            cancelled = isAbortRequested() || t instanceof AgentCancelledException;
            if (!cancelled) {
                failed = true;
            }
            ((CompletableFuture<Object>) job.future()).completeExceptionally(t);
        } finally {
            pending.decrementAndGet();
            synchronized (lock) {
                if (pending.get() > 0) {
                    phase = ResidentPhase.QUEUED; // 还有排队任务（不被取消）
                } else if (cancelled) {
                    phase = ResidentPhase.ABORTED; // 用户取消停驻（可 resume / 新 chat 自愈）
                } else if (failed) {
                    phase = ResidentPhase.ERROR; // 停驻可重试；下次 submit 自愈
                } else {
                    phase = ResidentPhase.IDLE;
                }
            }
            // 通知终态迁移（在锁外发；去重后仅值变化时发一次）
            ResidentPhase p = phase();
            if (p != ResidentPhase.IDLE && (phaseListener != null) && lastNotifiedPhase != p) {
                notifyPhase(p);
            }
        }
    }

    /** 任务被取消（对象关闭时排队未执行项）。 */
    public static final class CancelledException extends RuntimeException {
        public CancelledException(SessionId sessionId, String label) {
            super("常驻 agent 已关闭，任务未执行: session=" + sessionId + " label=" + label);
        }
    }
}
