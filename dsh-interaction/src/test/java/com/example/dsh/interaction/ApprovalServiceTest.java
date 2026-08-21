package com.example.dsh.interaction;

import com.example.dsh.core.model.SessionId;
import com.example.dsh.tool.ToolContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 审批服务测试：fail closed、一次性授予、超时按拒绝、策略 never 直接拒绝。
 */
class ApprovalServiceTest {

    private final SessionId sessionId = SessionId.of("sess_1");
    private final ToolContext context = ToolContext.builder().sessionId(sessionId).build();

    @SuppressWarnings("unchecked")
    private ObjectProvider<ApprovalProvider> providerOf(List<ApprovalProvider> providers) {
        ObjectProvider<ApprovalProvider> op = mock(ObjectProvider.class);
        when(op.orderedStream()).thenReturn(providers.stream());
        return op;
    }

    @Test
    void failsClosedWithoutProviders() {
        ApprovalService service = new ApprovalService(providerOf(List.of()),
                new ApprovalPolicyService(), 1000);
        ApprovalDecision decision = service.request("bash", "执行命令", context);
        assertEquals(ApprovalDecision.Status.UNAVAILABLE, decision.status());
        assertFalse(decision.allowed());
    }

    @Test
    void approvesOnceThenIsSingleUse() throws Exception {
        InMemoryApprovalProvider provider = new InMemoryApprovalProvider();
        ApprovalService service = new ApprovalService(providerOf(List.of(provider)),
                new ApprovalPolicyService(), 5000);
        AtomicReference<ApprovalRequest> captured = new AtomicReference<>();

        Thread approver = Thread.startVirtualThread(() -> {
            while (captured.get() == null) {
                var pending = provider.pendingRequests();
                if (!pending.isEmpty()) {
                    captured.set(pending.get(0));
                    provider.approve(pending.get(0).id());
                    break;
                }
            }
        });

        ApprovalDecision decision = service.request("bash", "执行命令", context);
        approver.join(5000);

        assertTrue(decision.allowed(), "应批准: " + decision.message());
        assertEquals(0, provider.pendingCount());
    }

    @Test
    void timeoutRejectsFailClosed() {
        InMemoryApprovalProvider provider = new InMemoryApprovalProvider();
        // 应答者存在但 50ms 内无人应答 → 超时按拒绝
        ApprovalService service = new ApprovalService(providerOf(List.of(provider)),
                new ApprovalPolicyService(), 50);
        ApprovalDecision decision = service.request("bash", "执行命令", context);
        assertEquals(ApprovalDecision.Status.REJECTED, decision.status());
        assertTrue(decision.message().contains("超时"));
    }

    @Test
    void neverPolicyDeniesWithoutAsking() {
        ApprovalPolicyService policies = new ApprovalPolicyService();
        policies.setPolicy(sessionId, ApprovalPolicy.NEVER);
        ApprovalService service = new ApprovalService(providerOf(List.of()), policies, 1000);
        ApprovalDecision decision = service.request("bash", "执行命令", context);
        assertEquals(ApprovalDecision.Status.REJECTED, decision.status());
        assertTrue(decision.message().contains("never"));
    }
}
