package com.bizfty.anchon.dsh.agent;

import com.bizfty.anchon.dsh.core.model.SessionId;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 请求系列跟踪器（C-①，对应上游 request/header 的系列判定）。
 * <p>
 * 语义：Java 每个 {@code executionId}（一次 {@code executeInner} turn）即一个系列边界 —
 * 同系列内的多 step / 重试共享同一 {@code seriesId}，只靠 {@code stepInSeries} 区分，
 * 与「每 step 一条 MODEL_REQUEST（全量 messages 内联）」的持久化语义配合，让下游能
 * 折叠「系列首请求 + 后续重复副本」。
 * <p>
 * 系列开启原因（对齐上游 EpochHeader reason，见 {@link ModelCallEventPayloads} 常量）：
 * <ul>
 *   <li>{@code initial}：本进程首次见到该会话（无进程内历史）；</li>
 *   <li>{@code resume}：新 execution（新 turn）且 header 指纹未变；</li>
 *   <li>{@code change}：header 指纹变化（model / system prompt / 可见工具 / options）；</li>
 *   <li>{@code series}：本 turn 发生了 surface 替换（历史压缩，遮蔽边界推进）。</li>
 * </ul>
 * 优先级：surface 替换 &gt; header 变化 &gt; 新 execution。进程重启后首见经
 * {@link #onTurnAfterRestart} 用事件库最近 header 指纹判定 durable resume/change（P2 已落地，
 * 见 docs/design-p2-hardening.md A 节）；同进程内走 {@link #onTurn}（内存态）。
 * <p>
 * 线程安全：per-session 状态存于 {@link ConcurrentHashMap}，多会话可并发推进。
 */
public class RequestSeriesTracker {

    /** 原因常量（与 ModelCallEventPayloads 词一致，双处冗余以便 agent 侧自包含引用）。 */
    public static final String REASON_INITIAL = "initial";
    public static final String REASON_RESUME = "resume";
    public static final String REASON_CHANGE = "change";
    public static final String REASON_SERIES = "series";

    /** 一次系列判定结果：seriesId + 开启原因。 */
    public record Series(String seriesId, String reason) {
    }

    private static final class State {
        String lastExecutionId;
        String lastHeaderFingerprint;
        int seriesCounter; // 本会话已开启的系列数（用于 seriesId 唯一后缀）
    }

    private final Map<String, State> states = new ConcurrentHashMap<>();

    /**
     * 一次 turn（execution）开始时的系列判定。
     *
     * @param sessionId            会话
     * @param executionId          本次执行标识（一 turn 一 execution）
     * @param headerFingerprint    header 指纹（model + system prompt + 工具集 + options 白名单）
     * @param surfaceReplaced      本 turn 是否发生 surface 替换（历史压缩推进遮蔽边界）
     * @return 本 turn 所属系列（开启原因按 {@code surfaceReplaced &gt; header 变化 &gt; 新 execution} 判定）
     */
    public Series onTurn(SessionId sessionId, String executionId, String headerFingerprint,
                         boolean surfaceReplaced) {
        String key = sessionId.value();
        State state = states.computeIfAbsent(key, k -> new State());

        String reason;
        boolean previousExists = state.lastExecutionId != null;
        boolean headerChanged = state.lastHeaderFingerprint != null
                && !Objects.equals(state.lastHeaderFingerprint, headerFingerprint);
        if (!previousExists) {
            reason = REASON_INITIAL;
        } else if (surfaceReplaced) {
            reason = REASON_SERIES;
        } else if (headerChanged) {
            reason = REASON_CHANGE;
        } else {
            reason = REASON_RESUME;
        }

        return advance(state, sessionId, executionId, headerFingerprint, reason);
    }

    /**
     * 重启/首见恢复判定：进程无该会话内存态时，用事件库最近一次 agent_turn header 指纹
     * 替代进程内历史，判定 durable resume/change（无 durable 指纹 → initial，与旧行为一致）。
     * 若调用时已有内存态（并发/误用）则直接回落 {@link #onTurn} 进程内判定。
     */
    public Series onTurnAfterRestart(SessionId sessionId, String executionId, String headerFingerprint,
                                     boolean surfaceReplaced, Optional<String> durableLastHeaderFingerprint) {
        String key = sessionId.value();
        State state = states.computeIfAbsent(key, k -> new State());
        if (state.lastExecutionId != null) {
            // 已有内存态（本进程已见过该会话）：与 onTurn 等价
            return onTurn(sessionId, executionId, headerFingerprint, surfaceReplaced);
        }
        String reason;
        if (durableLastHeaderFingerprint.isEmpty()) {
            reason = REASON_INITIAL;
        } else if (surfaceReplaced) {
            reason = REASON_SERIES;
        } else if (!Objects.equals(durableLastHeaderFingerprint.get(), headerFingerprint)) {
            reason = REASON_CHANGE;
        } else {
            reason = REASON_RESUME;
        }
        return advance(state, sessionId, executionId, headerFingerprint, reason);
    }

    /** 该会话是否有进程内系列状态（无 → 首见，可走 durable 恢复）。 */
    public boolean hasState(SessionId sessionId) {
        return states.containsKey(sessionId.value());
    }

    /** 推进状态并生成 seriesId（一 turn 一 series 边界；重启后 counter 从 1 重计，executionId 唯一）。 */
    private Series advance(State state, SessionId sessionId, String executionId,
                           String headerFingerprint, String reason) {
        state.seriesCounter++;
        String seriesId = "s-" + executionId + "-" + state.seriesCounter;
        state.lastExecutionId = executionId;
        state.lastHeaderFingerprint = headerFingerprint;
        return new Series(seriesId, reason);
    }
}
