package com.bizfty.anchon.dsh.api;

import com.bizfty.anchon.dsh.goal.Goal;
import com.bizfty.anchon.dsh.goal.GoalService;
import com.bizfty.anchon.dsh.storage.InMemoryStorageBackend;
import com.bizfty.anchon.dsh.storage.StorageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 目标端点测试：创建 → 查询 → 更新（complete）。
 */
class GoalControllerTest {

    private GoalService service() {
        @SuppressWarnings("unchecked")
        ObjectProvider<com.bizfty.anchon.dsh.storage.StorageBackend> sp = mock(ObjectProvider.class);
        when(sp.orderedStream()).thenReturn(Stream.of(new InMemoryStorageBackend()));
        return new GoalService(new StorageService(sp));
    }

    @Test
    void createGetUpdateFlow() {
        GoalService goals = service();
        GoalController controller = new GoalController(goals);

        var created = controller.create(new GoalController.CreateRequest("s_g1", "完成目标", 5));
        assertTrue(created.getStatusCode().is2xxSuccessful());
        Goal.GoalView view = (Goal.GoalView) created.getBody();
        assertEquals("active", view.phase());

        var got = controller.current("s_g1");
        assertTrue(got.getStatusCode().is2xxSuccessful());
        Goal.GoalView current = (Goal.GoalView) got.getBody();
        assertEquals(view.id(), current.id());

        var updated = controller.update(new GoalController.UpdateRequest(
                "s_g1", current.id(), current.revision(), "complete", null, null, null, null));
        assertTrue(updated.getStatusCode().is2xxSuccessful());
        assertEquals("complete", ((Goal.GoalView) updated.getBody()).phase());
    }

    @Test
    void duplicateCreateRejected() {
        GoalService goals = service();
        GoalController controller = new GoalController(goals);
        controller.create(new GoalController.CreateRequest("s_g2", "A", null));
        var dup = controller.create(new GoalController.CreateRequest("s_g2", "B", null));
        assertTrue(dup.getStatusCode().is4xxClientError());
    }

    @Test
    void casMismatchRejected() {
        GoalService goals = service();
        GoalController controller = new GoalController(goals);
        controller.create(new GoalController.CreateRequest("s_g3", "A", null));
        var bad = controller.update(new GoalController.UpdateRequest(
                "s_g3", "goal-wrong", 1, "pause", null, null, null, null));
        assertTrue(bad.getStatusCode().is4xxClientError());
    }

    @Test
    void noGoalReturns200WithNullGoal() {
        // 回归：无目标时不得 500（Map.of 不允许 null value 曾导致 NPE）
        GoalService goals = service();
        GoalController controller = new GoalController(goals);
        var got = controller.current("s_empty");
        assertTrue(got.getStatusCode().is2xxSuccessful(), "无目标应返回 200");
        java.util.Map<?, ?> body = (java.util.Map<?, ?>) got.getBody();
        assertTrue(body.containsKey("goal"));
        assertEquals(null, body.get("goal"), "goal 字段应为 JSON null");
    }
}
