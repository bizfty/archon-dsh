package com.bizfty.anchon.dsh.goal;

import com.bizfty.anchon.dsh.storage.InMemoryStorageBackend;
import com.bizfty.anchon.dsh.storage.StorageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 目标服务测试：创建/读取/生命周期/CAS/校验/持久化。
 */
class GoalServiceTest {

    private GoalService service() {
        @SuppressWarnings("unchecked")
        ObjectProvider<com.bizfty.anchon.dsh.storage.StorageBackend> sp = mock(ObjectProvider.class);
        when(sp.orderedStream()).thenReturn(Stream.of(new InMemoryStorageBackend()));
        return new GoalService(new StorageService(sp));
    }

    @Test
    void createThenCurrentRoundtrip() {
        GoalService service = service();
        Goal goal = service.create("s1", "完成目标 X", 10);
        assertTrue(goal.id().startsWith("goal-"));
        assertEquals(Goal.PHASE_ACTIVE, goal.phase());
        assertEquals(1, goal.revision());
        assertEquals(10, goal.maxGoalRounds());

        Goal current = service.current("s1").orElseThrow();
        assertEquals(goal.id(), current.id());
        assertEquals("完成目标 X", current.objective());
    }

    @Test
    void createRejectsExistingGoal() {
        GoalService service = service();
        service.create("s1", "A", null);
        assertThrows(IllegalArgumentException.class, () -> service.create("s1", "B", null));
    }

    @Test
    void lifecycleTransitions() {
        GoalService service = service();
        Goal goal = service.create("s1", "A", 5);
        // pause → resume → complete
        Goal paused = service.update("s1", goal.id(), goal.revision(), "pause", null, null, null, null);
        assertEquals(Goal.PHASE_PAUSED, paused.phase());
        assertEquals(2, paused.revision());
        Goal resumed = service.update("s1", paused.id(), paused.revision(), "resume", null, null, null, null);
        assertEquals(Goal.PHASE_ACTIVE, resumed.phase());
        Goal done = service.update("s1", resumed.id(), resumed.revision(), "complete", null, null, null, null);
        assertEquals(Goal.PHASE_COMPLETE, done.phase());
        assertEquals(4, done.revision());
    }

    @Test
    void editChangesObjectiveAndKeepsPhase() {
        GoalService service = service();
        Goal goal = service.create("s1", "旧目标", 5);
        Goal edited = service.update("s1", goal.id(), goal.revision(), "edit", "新目标", 8, null, null);
        assertEquals("新目标", edited.objective());
        assertEquals(8, edited.maxGoalRounds());
        assertEquals(Goal.PHASE_ACTIVE, edited.phase());
        assertEquals(2, edited.revision());
    }

    @Test
    void blockedRequiresCodeAndReason() {
        GoalService service = service();
        Goal goal = service.create("s1", "A", 5);
        assertThrows(IllegalArgumentException.class,
                () -> service.update("s1", goal.id(), goal.revision(), "blocked", null, null, "BAD CODE", "r"));
        assertThrows(IllegalArgumentException.class,
                () -> service.update("s1", goal.id(), goal.revision(), "blocked", null, null, "bad-code", "  "));
        Goal blocked = service.update("s1", goal.id(), goal.revision(), "blocked", null, null, "missing-dep", "缺少依赖");
        assertEquals(Goal.PHASE_BLOCKED, blocked.phase());
        assertEquals("missing-dep", blocked.blockedCode());
        assertEquals("缺少依赖", blocked.blockedReason());
    }

    @Test
    void casMismatchRejected() {
        GoalService service = service();
        Goal goal = service.create("s1", "A", 5);
        assertThrows(IllegalArgumentException.class,
                () -> service.update("s1", goal.id(), 99, "pause", null, null, null, null),
                "revision 不匹配应拒绝");
        assertThrows(IllegalArgumentException.class,
                () -> service.update("s1", "goal-wrong", goal.revision(), "pause", null, null, null, null),
                "goal_id 不匹配应拒绝");
    }

    @Test
    void unknownActionRejected() {
        GoalService service = service();
        Goal goal = service.create("s1", "A", 5);
        assertThrows(IllegalArgumentException.class,
                () -> service.update("s1", goal.id(), goal.revision(), "explode", null, null, null, null));
    }

    @Test
    void reserveNextRoundAdvancesOnlyWhenActiveAndWithinLimit() {
        GoalService service = service();
        Goal goal = service.create("s1", "A", 2);

        Goal r1 = service.reserveNextRound("s1", goal.id(), goal.revision()).orElseThrow();
        assertEquals(1, r1.roundsStarted());
        assertEquals(2, r1.revision());
        Goal r2 = service.reserveNextRound("s1", r1.id(), r1.revision()).orElseThrow();
        assertEquals(2, r2.roundsStarted());
        assertEquals(3, r2.revision());

        // 达到上限 → 不再预留
        assertTrue(service.reserveNextRound("s1", r2.id(), r2.revision()).isEmpty());
        // 非 active（complete）→ 不再预留
        Goal done = service.update("s1", r2.id(), r2.revision(), "complete", null, null, null, null);
        assertTrue(service.reserveNextRound("s1", done.id(), done.revision()).isEmpty());
    }

    @Test
    void reserveNextRoundRejectsStaleCas() {
        GoalService service = service();
        Goal goal = service.create("s1", "A", 5);
        assertThrows(IllegalArgumentException.class,
                () -> service.reserveNextRound("s1", goal.id(), 99));
        assertThrows(IllegalArgumentException.class,
                () -> service.reserveNextRound("s1", "goal-wrong", goal.revision()));
    }

    @Test
    void blockRoundLimitSetsBlocked() {
        GoalService service = service();
        Goal goal = service.create("s1", "A", 3);
        Goal blocked = service.blockRoundLimit("s1", goal.id(), goal.revision());
        assertEquals(Goal.PHASE_BLOCKED, blocked.phase());
        assertEquals("round-limit", blocked.blockedCode());
        assertTrue(blocked.blockedReason().contains("limit"));
    }

    @Test
    void disarmAndResumeControlArming() {
        GoalService service = service();
        service.create("s1", "A", 3);
        assertTrue(service.isArmed("s1"), "创建后默认 armed");
        service.disarm("s1");
        assertFalse(service.isArmed("s1"), "disarm 后未 armed");
        Goal goal = service.current("s1").orElseThrow();
        service.update("s1", goal.id(), goal.revision(), "resume", null, null, null, null);
        assertTrue(service.isArmed("s1"), "resume 后恢复 armed");
    }

    @Test
    void isArmedFalseWithoutActiveGoal() {
        GoalService service = service();
        assertFalse(service.isArmed("nope"), "无目标时未 armed");
        Goal goal = service.create("s1", "A", 3);
        service.update("s1", goal.id(), goal.revision(), "complete", null, null, null, null);
        assertFalse(service.isArmed("s1"), "goal 非 active 时未 armed");
    }
}
