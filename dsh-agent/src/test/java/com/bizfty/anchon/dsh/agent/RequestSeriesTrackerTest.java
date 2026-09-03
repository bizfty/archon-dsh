package com.bizfty.anchon.dsh.agent;

import com.bizfty.anchon.dsh.core.model.SessionId;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 请求系列跟踪器单测（C-①）：initial / resume / change / series 判定与优先级。
 */
class RequestSeriesTrackerTest {

    private final SessionId sid = SessionId.of("sess_series");
    private final RequestSeriesTracker tracker = new RequestSeriesTracker();

    @Test
    void firstTurnIsInitial() {
        RequestSeriesTracker.Series s = tracker.onTurn(sid, "run-1", "fp-A", false);
        assertEquals(RequestSeriesTracker.REASON_INITIAL, s.reason());
        assertTrue(s.seriesId().startsWith("s-run-1-"));
    }

    @Test
    void newExecutionSameHeaderIsResume() {
        tracker.onTurn(sid, "run-1", "fp-A", false);
        RequestSeriesTracker.Series s = tracker.onTurn(sid, "run-2", "fp-A", false);
        assertEquals(RequestSeriesTracker.REASON_RESUME, s.reason());
        assertNotEquals("s-run-1-1", s.seriesId());
    }

    @Test
    void headerChangeIsChange() {
        tracker.onTurn(sid, "run-1", "fp-A", false);
        RequestSeriesTracker.Series s = tracker.onTurn(sid, "run-2", "fp-B", false);
        assertEquals(RequestSeriesTracker.REASON_CHANGE, s.reason());
    }

    @Test
    void surfaceReplacementIsSeriesAndBeatsHeaderChange() {
        tracker.onTurn(sid, "run-1", "fp-A", false);
        RequestSeriesTracker.Series s = tracker.onTurn(sid, "run-2", "fp-B", true);
        assertEquals(RequestSeriesTracker.REASON_SERIES, s.reason());
    }

    @Test
    void perSessionStateIsIndependent() {
        SessionId other = SessionId.of("sess_series_other");
        tracker.onTurn(sid, "run-1", "fp-A", false);
        RequestSeriesTracker.Series firstOther = tracker.onTurn(other, "run-1", "fp-A", false);
        assertEquals(RequestSeriesTracker.REASON_INITIAL, firstOther.reason());
        RequestSeriesTracker.Series second = tracker.onTurn(sid, "run-2", "fp-A", false);
        assertEquals(RequestSeriesTracker.REASON_RESUME, second.reason());
        assertFalse(firstOther.seriesId().equals(second.seriesId()));
    }

    // ---- P2-① durable 恢复（重启后首见判定） ----

    @Test
    void restartWithSameDurableHeaderIsResume() {
        // 模拟重启：新 tracker 无内存态；事件库最近 agent_turn 指纹 = 本 turn 指纹
        RequestSeriesTracker fresh = new RequestSeriesTracker();
        RequestSeriesTracker.Series s = fresh.onTurnAfterRestart(
                sid, "run-9", "fp-A", false, Optional.of("fp-A"));
        assertEquals(RequestSeriesTracker.REASON_RESUME, s.reason());
        assertTrue(fresh.hasState(sid), "恢复后应建立内存态");
    }

    @Test
    void restartWithChangedDurableHeaderIsChange() {
        RequestSeriesTracker fresh = new RequestSeriesTracker();
        RequestSeriesTracker.Series s = fresh.onTurnAfterRestart(
                sid, "run-9", "fp-B", false, Optional.of("fp-A"));
        assertEquals(RequestSeriesTracker.REASON_CHANGE, s.reason());
    }

    @Test
    void restartWithSurfaceReplacementIsSeriesBeatingHeaderChange() {
        RequestSeriesTracker fresh = new RequestSeriesTracker();
        RequestSeriesTracker.Series s = fresh.onTurnAfterRestart(
                sid, "run-9", "fp-B", true, Optional.of("fp-A"));
        assertEquals(RequestSeriesTracker.REASON_SERIES, s.reason());
    }

    @Test
    void restartWithoutDurableHeaderIsInitial() {
        RequestSeriesTracker fresh = new RequestSeriesTracker();
        RequestSeriesTracker.Series s = fresh.onTurnAfterRestart(
                sid, "run-9", "fp-A", false, Optional.empty());
        assertEquals(RequestSeriesTracker.REASON_INITIAL, s.reason());
    }

    @Test
    void restartWithExistingMemoryStateFallsBackToInProcessDecision() {
        // 同进程内误用 onTurnAfterRestart：已有内存态 → 进程内判定（resume）
        tracker.onTurn(sid, "run-1", "fp-A", false);
        RequestSeriesTracker.Series s = tracker.onTurnAfterRestart(
                sid, "run-2", "fp-A", false, Optional.of("fp-OLD"));
        assertEquals(RequestSeriesTracker.REASON_RESUME, s.reason());
        assertTrue(tracker.hasState(sid));
    }
}
