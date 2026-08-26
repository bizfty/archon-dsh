package com.bizfty.anchon.dsh.interaction;

import com.bizfty.anchon.dsh.core.event.SessionEvent;
import com.bizfty.anchon.dsh.core.event.SessionEventBus;
import com.bizfty.anchon.dsh.core.event.SessionEventType;
import com.bizfty.anchon.dsh.core.model.SessionId;
import com.bizfty.anchon.dsh.tool.AgentTool;
import com.bizfty.anchon.dsh.tool.Tool;
import com.bizfty.anchon.dsh.tool.ToolCall;
import com.bizfty.anchon.dsh.tool.ToolContext;
import com.bizfty.anchon.dsh.tool.ToolExecutionPipeline;
import com.bizfty.anchon.dsh.tool.ToolEventPublisher;
import com.bizfty.anchon.dsh.tool.ToolRegistry;
import com.bizfty.anchon.dsh.tool.ToolResult;
import com.bizfty.anchon.dsh.tool.ToolSchema;
import com.bizfty.anchon.dsh.util.JsonUtils;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 审批门测试：requiresApproval 工具在管线中经审批门放行/拒绝；无需审批的工具不受影响。
 */
class ApprovalGateTest {

    private final SessionId sessionId = SessionId.of("sess_1");

    private ToolExecutionPipeline pipelineWith(ApprovalService approvalService, AgentTool... tools) {
        ApplicationContext ctx = mock(ApplicationContext.class);
        Map<String, AgentTool> beans = new java.util.LinkedHashMap<>();
        for (AgentTool t : tools) {
            beans.put(t.name(), t);
        }
        when(ctx.getBeansOfType(AgentTool.class)).thenReturn(beans);
        ToolRegistry registry = new ToolRegistry(ctx);
        SessionEventBus bus = new SessionEventBus();
        return new ToolExecutionPipeline(registry,
                List.of(new ApprovalGate(approvalService, registry, bus)),
                List.of(), new ToolEventPublisher(bus, new JsonUtils()), new JsonUtils());
    }

    private ApprovalService serviceWith(InMemoryApprovalProvider provider) {
        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<ApprovalProvider> op =
                mock(org.springframework.beans.factory.ObjectProvider.class);
        when(op.orderedStream()).thenReturn(java.util.stream.Stream.of(provider));
        return new ApprovalService(op, new ApprovalPolicyService(), 5000);
    }

    @Test
    void approvedToolExecutes() throws Exception {
        InMemoryApprovalProvider provider = new InMemoryApprovalProvider();
        ToolExecutionPipeline pipeline = pipelineWith(serviceWith(provider), new SensitiveTool());

        AtomicReference<String> pendingId = new AtomicReference<>();
        Thread approver = Thread.startVirtualThread(() -> {
            while (pendingId.get() == null) {
                var pending = provider.pendingRequests();
                if (!pending.isEmpty()) {
                    pendingId.set(pending.get(0).id());
                    provider.approve(pending.get(0).id());
                    break;
                }
            }
        });
        ToolResult result = pipeline.execute("sensitive_op", "{}",
                ToolContext.builder().sessionId(sessionId).build());
        approver.join(5000);

        assertTrue(result.success(), "批准后应执行: " + result.message());
        assertEquals("sensitive executed", result.message());
    }

    @Test
    void deniedToolIsBlocked() {
        InMemoryApprovalProvider provider = new InMemoryApprovalProvider();
        ToolExecutionPipeline pipeline = pipelineWith(serviceWith(provider), new SensitiveTool());
        // 不触发应答者，超时（50ms 内）→ 拒绝
        ToolResult result = pipeline.execute("sensitive_op", "{}",
                ToolContext.builder().sessionId(sessionId).build());
        assertFalse(result.success());
        assertTrue(result.message().contains("审批"));
    }

    @Test
    void nonApprovalToolBypassesGate() {
        ToolExecutionPipeline pipeline = pipelineWith(serviceWith(new InMemoryApprovalProvider()),
                new SensitiveTool(), new PlainTool());
        ToolResult result = pipeline.execute("plain_op", "{}",
                ToolContext.builder().sessionId(sessionId).build());
        assertTrue(result.success());
    }

    @Test
    void publishesApprovalRequestedEventBeforeBlocking() {
        InMemoryApprovalProvider provider = new InMemoryApprovalProvider();
        SessionEventBus bus = new SessionEventBus();
        List<SessionEvent> events = new ArrayList<>();
        bus.addListener(events::add);
        ApplicationContext ctx = mock(ApplicationContext.class);
        when(ctx.getBeansOfType(AgentTool.class))
                .thenReturn(Map.of("sensitive_op", new SensitiveTool()));
        ToolRegistry registry = new ToolRegistry(ctx);
        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<ApprovalProvider> op =
                mock(org.springframework.beans.factory.ObjectProvider.class);
        when(op.orderedStream()).thenReturn(java.util.stream.Stream.of(provider));
        ToolExecutionPipeline pipeline = new ToolExecutionPipeline(registry,
                List.of(new ApprovalGate(serviceWith(provider), registry, bus)),
                List.of(), new ToolEventPublisher(bus, new JsonUtils()), new JsonUtils());

        // 后台执行（会阻塞等待审批）
        java.util.concurrent.CompletableFuture.runAsync(() -> pipeline.execute("sensitive_op", "{}",
                ToolContext.builder().sessionId(sessionId).executionId("exec_approve").build()));
        // 等 APPROVAL_REQUESTED 事件发出
        long deadline = System.currentTimeMillis() + 3000;
        while (events.stream().noneMatch(e -> e.type() == SessionEventType.APPROVAL_REQUESTED)
                && System.currentTimeMillis() < deadline) {
            Thread.onSpinWait();
        }
        assertTrue(events.stream().anyMatch(e -> e.type() == SessionEventType.APPROVAL_REQUESTED),
                "阻塞审批前应发布 APPROVAL_REQUESTED");
        SessionEvent approvalEvent = events.stream()
                .filter(e -> e.type() == SessionEventType.APPROVAL_REQUESTED).findFirst().orElseThrow();
        assertEquals("sensitive_op", approvalEvent.string("tool"));
        assertEquals("exec_approve", approvalEvent.string("executionId"));
        // 应答后解除阻塞（等请求进入 pending 再批准）
        long d2 = System.currentTimeMillis() + 3000;
        while (provider.pendingRequests().isEmpty() && System.currentTimeMillis() < d2) {
            Thread.onSpinWait();
        }
        assertTrue(!provider.pendingRequests().isEmpty(), "审批请求应已挂起");
        provider.approve(provider.pendingRequests().get(0).id());
    }

    @Tool(name = "sensitive_op", description = "敏感操作", requiresApproval = true)
    static class SensitiveTool implements AgentTool {
        @Override
        public String name() {
            return "sensitive_op";
        }

        @Override
        public ToolSchema getSchema() {
            return ToolSchema.builder().name(name()).description("").build();
        }

        @Override
        public ToolResult execute(ToolCall call, ToolContext context) {
            return ToolResult.success("sensitive executed");
        }
    }

    @Tool(name = "plain_op", description = "普通操作")
    static class PlainTool implements AgentTool {
        @Override
        public String name() {
            return "plain_op";
        }

        @Override
        public ToolSchema getSchema() {
            return ToolSchema.builder().name(name()).description("").build();
        }

        @Override
        public ToolResult execute(ToolCall call, ToolContext context) {
            return ToolResult.success("plain executed");
        }
    }
}
