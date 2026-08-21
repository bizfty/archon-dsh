package com.example.dsh.tool;

import com.example.dsh.util.JsonUtils;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 工具执行管线 — pre-execute 门 → 执行 → post-execute 处理 → 事件发布。
 * <p>
 * 对应 DSH core/tools 的执行管线（pre-execute → guard → execute → post-execute → result）。
 * 门控拒绝是单调的：一旦某门拒绝，后续门不再放行。
 */
@Component
public class ToolExecutionPipeline {

    /** 工具超时执行器 — 虚拟线程，避免阻塞平台线程。 */
    private static final java.util.concurrent.ExecutorService TIMEOUT_EXECUTOR =
            java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();

    private final ToolRegistry registry;
    private final List<ToolPreExecuteGate> gates;
    private final List<ToolPostProcessor> postProcessors;
    private final ToolEventPublisher eventPublisher;
    private final JsonUtils jsonUtils;

    public ToolExecutionPipeline(ToolRegistry registry,
                                 List<ToolPreExecuteGate> gates,
                                 List<ToolPostProcessor> postProcessors,
                                 ToolEventPublisher eventPublisher,
                                 JsonUtils jsonUtils) {
        this.registry = registry;
        this.gates = gates.stream().sorted(Comparator.comparingInt(ToolPreExecuteGate::order)).toList();
        this.postProcessors = postProcessors.stream()
                .sorted(Comparator.comparingInt(ToolPostProcessor::order)).toList();
        this.eventPublisher = eventPublisher;
        this.jsonUtils = jsonUtils;
    }

    /**
     * 执行一次工具调用（从 Spring AI 模型返回的工具调用进入）。
     *
     * @param toolName   工具名
     * @param argsJson   模型给出的 JSON 参数
     * @param context    per-request 上下文
     * @return 模型可见的结果
     */
    public ToolResult execute(String toolName, String argsJson, ToolContext context) {
        ToolCall call = new ToolCall("call_" + UUID.randomUUID(), toolName, parseArgs(argsJson));

        // 1. 未知工具（对应 DSH UNKNOWN_TOOL）
        if (!registry.hasTool(toolName)) {
            ToolResult unknown = ToolResult.failure("未知工具: " + toolName);
            eventPublisher.publishToolResult(call, context, unknown);
            return unknown;
        }

        // 2. pre-execute 门（allow/deny/ask）
        for (ToolPreExecuteGate gate : gates) {
            Optional<String> denial;
            try {
                denial = gate.check(call, context);
            } catch (RuntimeException e) {
                eventPublisher.publishToolDenied(call, context, "门控异常: " + e.getMessage());
                return ToolResult.failure("门控异常: " + e.getMessage());
            }
            if (denial.isPresent()) {
                eventPublisher.publishToolDenied(call, context, denial.get());
                return ToolResult.failure("已拒绝: " + denial.get());
            }
        }

        eventPublisher.publishToolCall(call, context);

        // 3. 执行（支持工具声明超时；虚拟线程隔离，超时后返回结构化结果）
        ToolResult result;
        AgentTool tool = registry.getTool(toolName);
        long timeoutMs = tool.timeoutMs();
        if (timeoutMs > 0) {
            result = runWithTimeout(tool, call, context, timeoutMs);
        } else {
            try {
                result = tool.execute(call, context);
            } catch (Exception e) {
                result = ToolResult.failure("工具执行异常: " + e.getMessage());
            }
        }

        // 4. post-execute 处理
        for (ToolPostProcessor processor : postProcessors) {
            try {
                result = processor.process(call, context, result);
            } catch (Exception e) {
                result = ToolResult.failure("后处理异常: " + e.getMessage());
            }
        }

        eventPublisher.publishToolResult(call, context, result);
        return result;
    }

    /** 带超时的工具执行：超时 → TOOL_TIMEOUT 结构化失败（协作式：不硬杀工具线程）。 */
    private ToolResult runWithTimeout(AgentTool tool, ToolCall call, ToolContext context, long timeoutMs) {
        java.util.concurrent.CompletableFuture<ToolResult> future =
                java.util.concurrent.CompletableFuture.supplyAsync(() -> tool.execute(call, context), TIMEOUT_EXECUTOR);
        try {
            return future.orTimeout(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS).get();
        } catch (java.util.concurrent.ExecutionException e) {
            if (e.getCause() instanceof java.util.concurrent.TimeoutException) {
                eventPublisher.publishToolTimeout(call, context, timeoutMs);
                return ToolResult.failure("工具超时（>" + timeoutMs + " ms）");
            }
            return ToolResult.failure("工具执行异常: " + e.getCause().getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.failure("工具执行被中断");
        }
    }

    private java.util.Map<String, Object> parseArgs(String argsJson) {
        if (argsJson == null || argsJson.isBlank()) {
            return java.util.Map.of();
        }
        return jsonUtils.toMap(argsJson);
    }
}
