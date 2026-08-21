package com.example.dsh.agent;

import com.example.dsh.core.event.SessionEventBus;
import com.example.dsh.core.event.SessionEventType;
import com.example.dsh.core.model.Agent;
import com.example.dsh.core.model.MessageRole;
import com.example.dsh.core.model.Session;
import com.example.dsh.core.model.SessionId;
import com.example.dsh.core.model.SessionMessage;
import com.example.dsh.core.prompt.SystemPromptContext;
import com.example.dsh.core.prompt.SystemPromptService;
import com.example.dsh.core.prompt.ToolRef;
import com.example.dsh.llm.LlmGateway;
import com.example.dsh.session.SessionService;
import com.example.dsh.tool.AgentToolCallback;
import com.example.dsh.tool.ToolContext;
import com.example.dsh.tool.ToolEvent;
import com.example.dsh.tool.ToolExecutionPipeline;
import com.example.dsh.tool.ToolRegistry;
import com.example.dsh.tool.ToolResult;
import com.example.dsh.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Agent 循环 — 手动驱动 model→tool→model 的 turn/step 循环（对应 DSH core/agent-loop）。
 * <p>
 * 设计要点：
 * <ul>
 *   <li>不用 ChatClient 的内部工具循环（Spring AI 2.0 的 ToolCallingAdvisor 默认自动执行工具）；
 *       本服务直接调用 {@link LlmGateway}，工具调用原样返回，由
 *       {@link ToolExecutionPipeline}（门控/事件/后处理）接管。</li>
 *   <li>每步的 system prompt + 工具 schema + 派生消息都会重发（与 DSH 一致）；
 *       assistant（含工具调用）与 tool 结果逐条落会话日志（Model-visible ⟺ logged）。</li>
 *   <li>turn 失败只结束本次运行；max-steps 防止失控循环。</li>
 * </ul>
 */
@Service
public class AgentLoopService {

    private static final Logger log = LoggerFactory.getLogger(AgentLoopService.class);

    /** 并行工具执行器 — 虚拟线程。 */
    private static final java.util.concurrent.ExecutorService PARALLEL_EXECUTOR =
            java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();

    private final LlmGateway llmGateway;
    private final SessionService sessionService;
    private final ToolRegistry toolRegistry;
    private final ToolExecutionPipeline pipeline;
    private final SystemPromptService systemPromptService;
    private final AgentProvider agentProvider;
    private final AgentScopeRegistry agentScopeRegistry;
    private final com.example.dsh.credentials.CredentialService credentialService;
    private final com.example.dsh.compaction.SpillService spillService;
    private final MessageProjector messageProjector;
    private final SessionEventBus eventBus;
    private final JsonUtils jsonUtils;
    private final AgentLoopProperties properties;
    private final com.example.dsh.compaction.CompactionService compactionService;
    private final com.example.dsh.compaction.CompactionBoundaryStore compactionBoundaryStore;
    private final com.example.dsh.sandbox.SandboxPolicyService sandboxPolicyService;
    private final ModelRetryPolicy retryPolicy;
    private final com.example.dsh.settings.SettingsService settingsService;
    private final SessionTitleService sessionTitleService;

    public AgentLoopService(LlmGateway llmGateway,
                            SessionService sessionService,
                            ToolRegistry toolRegistry,
                            ToolExecutionPipeline pipeline,
                            SystemPromptService systemPromptService,
                            AgentProvider agentProvider,
                            AgentScopeRegistry agentScopeRegistry,
                            com.example.dsh.credentials.CredentialService credentialService,
                            com.example.dsh.compaction.SpillService spillService,
                            MessageProjector messageProjector,
                            SessionEventBus eventBus,
                            JsonUtils jsonUtils,
                            AgentLoopProperties properties,
                            com.example.dsh.compaction.CompactionService compactionService,
                            com.example.dsh.compaction.CompactionBoundaryStore compactionBoundaryStore,
                            com.example.dsh.sandbox.SandboxPolicyService sandboxPolicyService,
                            ModelRetryPolicy retryPolicy,
                            com.example.dsh.settings.SettingsService settingsService,
                            SessionTitleService sessionTitleService) {
        this.llmGateway = llmGateway;
        this.sessionService = sessionService;
        this.toolRegistry = toolRegistry;
        this.pipeline = pipeline;
        this.systemPromptService = systemPromptService;
        this.agentProvider = agentProvider;
        this.agentScopeRegistry = agentScopeRegistry;
        this.credentialService = credentialService;
        this.spillService = spillService;
        this.messageProjector = messageProjector;
        this.eventBus = eventBus;
        this.jsonUtils = jsonUtils;
        this.properties = properties;
        this.compactionService = compactionService;
        this.compactionBoundaryStore = compactionBoundaryStore;
        this.sandboxPolicyService = sandboxPolicyService;
        this.retryPolicy = retryPolicy;
        this.settingsService = settingsService; // 可空：无 settings 时回退 AgentLoopProperties
        this.sessionTitleService = sessionTitleService; // 可空：无标题服务则跳过
    }

    /** 生效温度：settings.agent.temperature > AgentLoopProperties.temperature。 */
    public double effectiveTemperature() {
        if (settingsService != null && settingsService.get("agent", "temperature") instanceof Number n) {
            return n.doubleValue();
        }
        return properties.temperature();
    }

    /** 生效最大步数：settings.agent.max-steps > properties。 */
    public int effectiveMaxSteps() {
        return settingsService != null
                ? settingsService.getInt("agent", "max-steps", properties.maxSteps())
                : properties.maxSteps();
    }

    /** 生效并行上限：settings.agent.max-parallel-tool-calls > properties。 */
    public int effectiveMaxParallelToolCalls() {
        return settingsService != null
                ? settingsService.getInt("agent", "max-parallel-tool-calls", properties.maxParallelToolCalls())
                : properties.maxParallelToolCalls();
    }

    /** 非流式运行一个 turn。 */
    public AgentRunResult run(AgentRunRequest request) {
        return execute(request, null, null);
    }

    /**
     * 手动压缩（对应 DSH command-compact 的 `/compact`）：即使低于自动压力阈值，
     * 也压缩一个平衡的较老历史段（边界后有效历史），持久化摘要并更新遮蔽边界。
     * <p>
     * 不经过模型 turn；返回人类可读的状态文本。无可压缩历史时返回
     * "No compactable history yet."（不写任何摘要/边界）。
     */
    public String manualCompact(SessionId sessionId) {
        List<SessionMessage> history = sessionService.listMessages(sessionId);
        int boundary = compactionBoundaryStore == null ? 0 : compactionBoundaryStore.read(sessionId);
        int effectiveFrom = Math.min(boundary, history.size());
        List<SessionMessage> effective = history.subList(effectiveFrom, history.size());
        if (effective.size() < 2) {
            return "No compactable history yet.";
        }
        var plan = compactionService.compress(effective, llmGateway);
        sessionService.append(sessionId, MessageRole.USER,
                "（历史压缩摘要）\n" + plan.summaryText(), null, null, null);
        int newBoundary = effectiveFrom + plan.compressedCount();
        if (compactionBoundaryStore != null) {
            compactionBoundaryStore.write(sessionId, newBoundary);
        }
        log.info("[Compaction] /compact session={} 手动压缩: 省略 {} 条，保留 {} 条，新边界 {}",
                sessionId, plan.compressedCount(), plan.tail().size(), newBoundary);
        long savedTokens = plan.tail().stream()
                .mapToLong(m -> m.content() == null ? 0 : m.content().length() / 4L)
                .sum();
        return "已压缩 " + plan.compressedCount() + " 条历史（保留 " + plan.tail().size()
                + " 条，预估节省 " + savedTokens + " tokens）。";
    }

    /**
     * 流式运行一个 turn。
     *
     * @param onToken     文本增量回调
     * @param onToolEvent 工具事件回调
     */
    public AgentRunResult stream(AgentRunRequest request,
                                 Consumer<String> onToken,
                                 Consumer<ToolEvent> onToolEvent) {
        if (onToken == null) {
            throw new IllegalArgumentException("onToken 不能为 null");
        }
        return execute(request, onToken, onToolEvent);
    }

    private AgentRunResult execute(AgentRunRequest request,
                                   Consumer<String> onToken,
                                   Consumer<ToolEvent> onToolEvent) {
        SessionId sessionId = request.sessionId();
        Session session = sessionService.getSession(sessionId);
        Agent agent = agentProvider.resolve(request.agentId());
        String model = resolveModel(session, request.modelOverride());
        // API key 优先级：请求级覆盖（如用户 profile key）> agent 配置的凭据引用 > 全局 key
        String apiKey = request.apiKeyOverride();
        if ((apiKey == null || apiKey.isBlank()) && agent.credentialRef() != null && credentialService != null) {
            apiKey = credentialService.resolve(com.example.dsh.credentials.CredentialRef.parse(agent.credentialRef()))
                    .orElse(null);
        }
        String executionId = request.executionId() == null ? "run-" + UUID.randomUUID() : request.executionId();

        // ---- turn 打开 ----
        eventBus.publish(sessionId, SessionEventType.TURN_START,
                Map.of("executionId", executionId, "model", model));
        sessionService.append(sessionId, MessageRole.USER, request.userMessage(), null, null, null);
        eventBus.publish(sessionId, SessionEventType.USER_MESSAGE, Map.of("content", request.userMessage()));
        if (sessionTitleService != null) {
            sessionTitleService.maybeTitle(sessionId, request.userMessage());
        }

        // ---- 作用域 + 组装 system prompt（每 turn 一次；后续 step 复用文本）----
        // 作用域注册表解析（无注册时退化为 agent 配置）；扩展：preset/settings 可注册
        AgentScope scope = agentScopeRegistry == null
                ? AgentScope.forAgent(agent)
                : agentScopeRegistry.resolve(agent);
        List<ToolRef> toolRefs = toolRegistry.toToolRefs(jsonUtils).stream()
                .filter(ref -> scope.toolVisibility().test(ref.name()))
                .toList();
        Map<String, String> variables = SystemPromptService.baseVariables(session, agent);
        String systemPrompt = systemPromptService.assemble(SystemPromptContext.builder()
                .session(session)
                .agent(agent)
                .toolRefs(toolRefs)
                .variables(variables)
                .build(), scope.extraSections());

        // ---- 历史（含压缩）+ 工具回调 ----
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(systemPrompt));
        List<SessionMessage> history = sessionService.listMessages(sessionId);
        CompactionView compaction = maybeCompact(sessionId, history);
        history = compaction.messages();
        // 当前用户消息已在上文持久化（history 最后一条），回放时排除，避免重复注入。
        // 压缩发生时本 turn 已用「摘要 + 尾部」视图；未压缩时从遮蔽边界起播，
        // 不再重发已被摘要覆盖的历史头（shadow boundary，对应 DSH surfaceOp replace）。
        int from = compaction.compacted()
                ? Math.max(0, history.size() - properties.maxHistoryMessages())
                : Math.max(compaction.boundary(), Math.max(0, history.size() - properties.maxHistoryMessages()));
        int toExclusive = history.size() - 1; // 排除刚写入的当前 USER 消息
        // 配对过滤：滑动窗口/压缩边界可能把 assistant(tool_calls) 与其 TOOL 响应切开，
        // 导致模型收到 "tool_calls 无对应 tool 消息" 的 400（OpenAI 校验）。
        // 规则：只保留"完整对" — assistant 的 tool_calls 必须被窗口内 TOOL 全覆盖（否则只发文本），
        // TOOL 只发其 tool_call_id 属于某个完整 assistant 的（孤立 TOOL 跳过）。
        java.util.Set<String> windowToolIds = new java.util.HashSet<>();
        for (int i = from; i < toExclusive; i++) {
            SessionMessage m = history.get(i);
            if (m.role() == MessageRole.TOOL && m.toolCallId() != null) {
                windowToolIds.add(m.toolCallId());
            }
        }
        java.util.Set<String> completeAssistantToolIds = new java.util.HashSet<>();
        for (int i = from; i < toExclusive; i++) {
            SessionMessage m = history.get(i);
            if (m.role() == MessageRole.ASSISTANT && m.toolCallsJson() != null) {
                var tcs = parseToolCalls(m.toolCallsJson());
                if (!tcs.isEmpty() && tcs.stream().allMatch(tc -> windowToolIds.contains(tc.id()))) {
                    tcs.forEach(tc -> completeAssistantToolIds.add(tc.id()));
                }
            }
        }
        for (int i = from; i < toExclusive; i++) {
            SessionMessage m = history.get(i);
            if (m.role() == MessageRole.ASSISTANT) {
                var tcs = m.toolCallsJson() == null ? List.<AssistantMessage.ToolCall>of() : parseToolCalls(m.toolCallsJson());
                if (!tcs.isEmpty()) {
                    boolean complete = tcs.stream().allMatch(tc -> windowToolIds.contains(tc.id()));
                    if (!complete) {
                        // tool_calls 未被窗口内 TOOL 完整覆盖：只发文本，避免 400
                        messages.add(new AssistantMessage(m.content() == null ? "" : m.content()));
                        continue;
                    }
                }
                messages.add(messageProjector.project(m));
            } else if (m.role() == MessageRole.TOOL && m.toolCallId() != null
                    && !completeAssistantToolIds.contains(m.toolCallId())) {
                continue; // 孤立 TOOL：其 assistant(tool_calls) 不在窗口内（或已被剥离），跳过
            } else {
                messages.add(messageProjector.project(m));
            }
        }
        messages.add(new UserMessage(request.userMessage()));

        ToolContext toolContext = ToolContext.builder()
                .sessionId(sessionId)
                .agentId(agent.id())
                .executionId(executionId)
                .cwd(session.cwd())
                .delegationDepth(request.delegationDepth())
                .sandboxMode(sandboxPolicyService.resolve(sessionId))
                .build();
        List<org.springframework.ai.tool.ToolCallback> callbacks = toolRegistry.allTools().stream()
                .filter(t -> scope.toolVisibility().test(t.name()))
                .map(t -> new AgentToolCallback(t, toolContext, jsonUtils, pipeline))
                .map(c -> (org.springframework.ai.tool.ToolCallback) c)
                .toList();

        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(model)
                .temperature(effectiveTemperature())
                .toolCallbacks(callbacks)
                .build();

        // ---- step 循环 ----
        int steps = 0;
        int toolCalls = 0;
        while (true) {
            steps++;
            if (steps > effectiveMaxSteps()) {
                throw new AgentLoopException("超过最大步数上限: " + effectiveMaxSteps());
            }
            eventBus.publish(sessionId, SessionEventType.STEP_START, Map.of("step", steps));

            AssistantMessage assistant;
            if (onToken != null) {
                assistant = streamStep(sessionId, messages, options, onToken, model, apiKey);
            } else {
                assistant = callStep(sessionId, messages, options, model, apiKey);
            }

            if (!assistant.hasToolCalls()) {
                // ---- 终态：assistant 文本 ----
                String content = assistant.getText() == null ? "" : assistant.getText();
                sessionService.append(sessionId, MessageRole.ASSISTANT, content, null, null, null);
                eventBus.publish(sessionId, SessionEventType.ASSISTANT_MESSAGE, Map.of("content", content));
                eventBus.publish(sessionId, SessionEventType.TURN_END, Map.of(
                        "steps", steps, "tool_calls", toolCalls, "finish", "stop"));
                log.info("[AgentLoop] session={} turn 完成: steps={} toolCalls={}", sessionId, steps, toolCalls);
                return AgentRunResult.text(content, sessionId, steps, toolCalls);
            }

            // ---- 工具调用轮（并行分类执行，结果保持模型顺序）----
            messages.add(assistant);
            List<AssistantMessage.ToolCall> toolCallsList = assistant.getToolCalls();
            toolCalls += toolCallsList.size();
            sessionService.append(sessionId, MessageRole.ASSISTANT,
                    assistant.getText() == null ? "" : assistant.getText(),
                    null, null, jsonUtils.toJson(serializeToolCalls(toolCallsList)));
            eventBus.publish(sessionId, SessionEventType.ASSISTANT_MESSAGE,
                    Map.of("tool_calls", toolCallsList.size()));

            ToolResult[] results = executeToolCalls(toolCallsList, toolContext, scope.toolVisibility());

            List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
            List<String> additionalContexts = new ArrayList<>();
            for (int idx = 0; idx < toolCallsList.size(); idx++) {
                AssistantMessage.ToolCall tc = toolCallsList.get(idx);
                ToolResult result = results[idx];
                String resultJson = jsonUtils.toJson(result.toMap());
                if (spillService != null) {
                    // 超大工具结果 → 转存文件 + 行内预览定位符（模型面与日志同为有界替换）
                    resultJson = spillService.maybeSpill(sessionId.value(), tc.name(), tc.id(), resultJson);
                }
                responses.add(new ToolResponseMessage.ToolResponse(tc.id(), tc.name(), resultJson));
                sessionService.append(sessionId, MessageRole.TOOL, resultJson, tc.id(), tc.name(), null);
                if (onToolEvent != null) {
                    onToolEvent.accept(result.success()
                            ? ToolEvent.toolResult(tc.name(), true, result.message(), result.data())
                            : ToolEvent.toolError(tc.name(), result.message(), result.data()));
                }
                if (result.additionalContexts() != null) {
                    additionalContexts.addAll(result.additionalContexts());
                }
            }
            // 消息顺序：assistant(工具调用) → tool 结果 → 附加上下文
            messages.add(ToolResponseMessage.builder().responses(responses).build());
            for (String context : additionalContexts) {
                if (context == null || context.isBlank()) {
                    continue;
                }
                sessionService.append(sessionId, MessageRole.USER, context, null, null, null);
                messages.add(new UserMessage(context));
            }
        }
    }

    /**
     * 执行一轮工具调用（对应 DSH 并行调度：isConcurrencySafe 精确 true 才并行）。
     * <p>
     * 连续 safe 调用进入有界滚动池（maxParallelToolCalls）；exclusive 调用（含未知工具）
     * 为串行屏障。结果按下标回填，持久化/上下文保持模型顺序。
     */
    private ToolResult[] executeToolCalls(List<AssistantMessage.ToolCall> toolCallsList, ToolContext toolContext,
                                          java.util.function.Predicate<String> visible) {
        ToolResult[] results = new ToolResult[toolCallsList.size()];
        java.util.concurrent.Semaphore limiter =
                new java.util.concurrent.Semaphore(effectiveMaxParallelToolCalls());
        int i = 0;
        while (i < toolCallsList.size()) {
            AssistantMessage.ToolCall tc = toolCallsList.get(i);
            if (!visible.test(tc.name())) {
                // 受限工具：拒绝执行（defense-in-depth；正常流程中模型看不到该工具 schema）
                results[i] = ToolResult.failure("工具 " + tc.name() + " 对本 agent 不可用（restricted）");
                i++;
                continue;
            }
            if (!isConcurrencySafe(tc.name())) {
                // 串行屏障
                results[i] = pipeline.execute(tc.name(), tc.arguments(), toolContext);
                i++;
                continue;
            }
            // 收集连续 safe 段
            List<Integer> batch = new ArrayList<>();
            while (i < toolCallsList.size() && isConcurrencySafe(toolCallsList.get(i).name())) {
                batch.add(i);
                i++;
            }
            List<java.util.concurrent.CompletableFuture<ToolResult>> futures = batch.stream()
                    .map(idx -> java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                        try {
                            limiter.acquire();
                            AssistantMessage.ToolCall c = toolCallsList.get(idx);
                            return pipeline.execute(c.name(), c.arguments(), toolContext);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return ToolResult.failure("并行执行被中断");
                        } finally {
                            limiter.release();
                        }
                    }, PARALLEL_EXECUTOR))
                    .toList();
            java.util.concurrent.CompletableFuture.allOf(futures.toArray(new java.util.concurrent.CompletableFuture[0]))
                    .join();
            for (int k = 0; k < batch.size(); k++) {
                results[batch.get(k)] = futures.get(k).join();
            }
        }
        return results;
    }

    private boolean isConcurrencySafe(String toolName) {
        return toolRegistry.hasTool(toolName) && toolRegistry.getTool(toolName).isConcurrencySafe();
    }

    /** 非流式 step（带重试：瞬时失败退避重试，对应 DSH turn 级恢复点）。 */
    private AssistantMessage callStep(SessionId sessionId, List<Message> messages,
                                      OpenAiChatOptions options, String model, String apiKey) {
        eventBus.publish(sessionId, SessionEventType.MODEL_REQUEST, Map.of("model", model));
        ChatResponse response = retryPolicy.executeWithRetry(() -> llmGateway.call(messages, options, apiKey));
        return response.getResult().getOutput();
    }

    /**
     * 流式 step：转发文本增量；累积工具调用（最后一块为准）。
     * <p>
     * 仅当尚未发出任何 token 时才允许重试（已发 token 后失败不重试 —
     * 与 DSH 一致：重试会开新 turn，不能吞掉已输出的内容）。
     */
    private AssistantMessage streamStep(SessionId sessionId, List<Message> messages,
                                        OpenAiChatOptions options, Consumer<String> onToken, String model,
                                        String apiKey) {
        eventBus.publish(sessionId, SessionEventType.MODEL_REQUEST, Map.of("model", model));
        StringBuilder text = new StringBuilder();
        AtomicReference<List<AssistantMessage.ToolCall>> toolCalls = new AtomicReference<>();
        AtomicBoolean forwarded = new AtomicBoolean(false);
        retryPolicy.executeWithRetry(() -> {
            if (forwarded.get()) {
                throw new ModelRetryPolicy.NonRetryableException("流式已输出 token，不重试");
            }
            Flux<ChatResponse> flux = llmGateway.stream(messages, options, apiKey);
            flux.doOnNext(chunk -> {
                Generation generation = chunk.getResult();
                if (generation != null) {
                    AssistantMessage partial = generation.getOutput();
                    if (partial != null) {
                        String delta = partial.getText();
                        if (delta != null && !delta.isEmpty()) {
                            text.append(delta);
                            forwarded.set(true);
                            onToken.accept(delta);
                        }
                        if (partial.hasToolCalls()) {
                            toolCalls.set(partial.getToolCalls());
                        }
                    }
                }
            }).blockLast();
            return null;
        });
        return AssistantMessage.builder()
                .content(text.toString())
                .toolCalls(toolCalls.get() == null ? List.of() : toolCalls.get())
                .build();
    }

    /**
     * 历史压缩：token 超阈值时用「摘要 + 尾部」替换派生历史。
     * 摘要作为 USER 消息持久化（表面替换语义，原历史行保留，可确定性回放）。
     */
    /**
     * 压缩入口：返回本 turn 使用的历史视图 + 是否刚压缩 + 遮蔽边界。
     * <p>
     * 边界语义（shadow boundary）：压缩评估与压缩都基于「边界之后」的有效历史，
     * 新边界 = 旧边界 + 本次压缩的头条数（绝对日志下标）；摘要持久化后，后续 turn
     * 从边界起播，不再重放已被摘要覆盖的旧头。
     */
    private CompactionView maybeCompact(SessionId sessionId, List<SessionMessage> history) {
        int boundary = compactionBoundaryStore == null ? 0 : compactionBoundaryStore.read(sessionId);
        int effectiveFrom = Math.min(boundary, history.size());
        List<SessionMessage> effective = history.subList(effectiveFrom, history.size());
        if (!compactionService.needsCompaction(effective)) {
            return new CompactionView(history, false, boundary);
        }
        log.info("[Compaction] session={} 历史 {} 条消息超阈值（有效 {} 条），开始压缩",
                sessionId, history.size(), effective.size());
        var plan = compactionService.compress(effective, llmGateway);
        sessionService.append(sessionId, MessageRole.USER,
                "（历史压缩摘要）\n" + plan.summaryText(), null, null, null);
        int newBoundary = effectiveFrom + plan.compressedCount();
        if (compactionBoundaryStore != null) {
            compactionBoundaryStore.write(sessionId, newBoundary);
        }
        log.info("[Compaction] session={} 压缩完成: 省略 {} 条，保留 {} 条，新边界 {}",
                sessionId, plan.compressedCount(), plan.tail().size(), newBoundary);
        List<SessionMessage> compacted = new ArrayList<>();
        compacted.add(new SessionMessage(
                "msg_summary_" + UUID.randomUUID(), sessionId, MessageRole.USER,
                "（历史压缩摘要）\n" + plan.summaryText(), null, null, null, Long.MAX_VALUE, java.time.Instant.now()));
        compacted.addAll(plan.tail());
        return new CompactionView(compacted, true, newBoundary);
    }

    /** 压缩视图：本 turn 历史 + 是否刚压缩 + 遮蔽边界（未压缩时为旧边界）。 */
    private record CompactionView(List<SessionMessage> messages, boolean compacted, int boundary) {
    }

    private String resolveModel(Session session, String override) {
        if (override != null && !override.isBlank()) {
            return override;
        }
        if (session.model() != null && !session.model().isBlank()) {
            return session.model();
        }
        Agent agent = agentProvider.defaultAgent();
        if (agent.model() != null && !agent.model().isBlank()) {
            return agent.model();
        }
        return llmGateway.defaultModel();
    }

    private List<Map<String, String>> serializeToolCalls(List<AssistantMessage.ToolCall> toolCalls) {
        return toolCalls.stream()
                .map(tc -> {
                    Map<String, String> m = new LinkedHashMap<>();
                    m.put("id", tc.id());
                    m.put("type", tc.type());
                    m.put("name", tc.name());
                    m.put("arguments", tc.arguments());
                    return m;
                })
                .toList();
    }

    /** 反序列化持久化的 tool_calls（与 serializeToolCalls 互逆；null/空 → 空列表）。 */
    private List<AssistantMessage.ToolCall> parseToolCalls(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        List<Map<String, Object>> list = jsonUtils.toList(json);
        return list.stream()
                .map(m -> new AssistantMessage.ToolCall(
                        String.valueOf(m.get("id")),
                        m.get("type") == null ? "function" : String.valueOf(m.get("type")),
                        String.valueOf(m.get("name")),
                        String.valueOf(m.get("arguments"))))
                .toList();
    }
}
