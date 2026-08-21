package com.example.dsh.interaction;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 进程内审批应答者 — 请求挂起等待，由 approve/reject 完成。
 * <p>
 * Web 层可暴露待审批列表 + 审批端点，调 approve/reject 即完成应答。
 */
@Component
public class InMemoryApprovalProvider implements ApprovalProvider {

    private final Map<String, ApprovalRequest> requests = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<ApprovalDecision>> pending = new ConcurrentHashMap<>();

    @Override
    public String name() {
        return "in-memory";
    }

    @Override
    public Optional<ApprovalDecision> request(ApprovalRequest request, long timeoutMs) {
        CompletableFuture<ApprovalDecision> future = new CompletableFuture<>();
        requests.put(request.id(), request);
        pending.put(request.id(), future);
        try {
            ApprovalDecision decision = future.orTimeout(timeoutMs, TimeUnit.MILLISECONDS).get();
            return Optional.of(decision);
        } catch (java.util.concurrent.ExecutionException e) {
            if (e.getCause() instanceof java.util.concurrent.TimeoutException) {
                return Optional.of(ApprovalDecision.rejected("审批超时（>" + timeoutMs + " ms），按拒绝处理"));
            }
            return Optional.of(ApprovalDecision.unavailable("审批应答异常: " + e.getCause().getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.of(ApprovalDecision.unavailable("审批应答被中断"));
        } finally {
            requests.remove(request.id());
            pending.remove(request.id());
        }
    }

    /** 待审批请求列表（供 UI 查询）。 */
    public List<ApprovalRequest> pendingRequests() {
        return List.copyOf(requests.values());
    }

    public void approve(String requestId) {
        CompletableFuture<ApprovalDecision> future = pending.get(requestId);
        if (future != null) {
            future.complete(ApprovalDecision.allowed("已批准"));
        }
    }

    public void reject(String requestId, String reason) {
        CompletableFuture<ApprovalDecision> future = pending.get(requestId);
        if (future != null) {
            future.complete(ApprovalDecision.rejected(reason == null ? "已拒绝" : reason));
        }
    }

    /** 待审批数量（测试/UI 用）。 */
    public int pendingCount() {
        return pending.size();
    }
}
