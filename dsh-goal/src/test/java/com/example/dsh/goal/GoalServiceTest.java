package com.example.dsh.goal;

import com.example.dsh.storage.InMemoryStorageBackend;
import com.example.dsh.storage.StorageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        ObjectProvider<com.example.dsh.storage.StorageBackend> sp = mock(ObjectProvider.class);
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
}
