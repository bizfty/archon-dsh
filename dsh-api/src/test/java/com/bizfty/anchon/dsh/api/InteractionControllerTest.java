package com.bizfty.anchon.dsh.api;

import com.bizfty.anchon.dsh.interaction.InMemoryApprovalProvider;
import com.bizfty.anchon.dsh.interaction.InMemoryUserQuestionProvider;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 人机协作端点测试：审批/问答的挂起 → 应答 → 恢复闭环。
 */
class InteractionControllerTest {

    @Test
    void approvalLifecycleCompletesPendingRequest() {
        InMemoryApprovalProvider provider = new InMemoryApprovalProvider();
        InteractionController controller = new InteractionController(provider,
                new InMemoryUserQuestionProvider());

        // 后台线程模拟模型工具调用阻塞等审批
        CompletableFuture<String> decision = CompletableFuture.supplyAsync(() -> {
            var d = provider.request(new com.bizfty.anchon.dsh.interaction.ApprovalRequest(
                    "appr_test", "bash", "执行命令", null, java.time.Instant.now()), 5000);
            return d.map(dd -> dd.status().name()).orElse("none");
        });

        // 等请求出现
        waitUntil(() -> !controller.pendingApprovals().isEmpty());
        List<Map<String, Object>> pending = controller.pendingApprovals();
        assertEquals("bash", pending.get(0).get("tool"));
        String id = (String) pending.get(0).get("id");

        controller.approve(id);
        assertEquals("ALLOWED", decision.join());
        assertTrue(controller.pendingApprovals().isEmpty());
    }

    @Test
    void questionLifecycleCompletesPendingRequest() {
        InMemoryUserQuestionProvider provider = new InMemoryUserQuestionProvider();
        InteractionController controller = new InteractionController(
                new InMemoryApprovalProvider(), provider);

        CompletableFuture<String> answer = CompletableFuture.supplyAsync(() -> {
            var a = provider.ask(new com.bizfty.anchon.dsh.interaction.UserQuestion(
                    "q_test", "继续？", List.of("是", "否"), false), 5000);
            return a.orElse("none");
        });

        waitUntil(() -> !controller.pendingQuestions().isEmpty());
        String id = (String) controller.pendingQuestions().get(0).get("id");
        controller.answer(id, Map.of("answer", "是"));
        assertEquals("是", answer.join());
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 5000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.onSpinWait();
        }
        assertTrue(condition.getAsBoolean(), "等待条件超时");
    }
}
