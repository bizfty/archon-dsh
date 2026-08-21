package com.example.dsh.subagent;

import com.example.dsh.agent.AgentLoopService;
import com.example.dsh.agent.AgentRunRequest;
import com.example.dsh.agent.AgentRunResult;
import com.example.dsh.core.model.Session;
import com.example.dsh.core.model.SessionId;
import com.example.dsh.session.SessionService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * 子代理运行器 — 进程内委托（对应 DSH subagent/subagent-in-process-driver）。
 * <p>
 * 一个子代理 = 一个持久子会话；start 同步等待首轮结果，
 * followup 继续同一子会话（continuable）。深度守卫：delegationDepth ≥ max 拒绝。
 * <p>
 * AgentLoopService 用 ObjectProvider 懒解析：ToolRegistry 构造时 getBeansOfType
 * 会实例化本模块工具，若此时强依赖 AgentLoopService 会形成
 * ToolRegistry → SubagentTool → SubagentRunner → AgentLoopService → ToolRegistry 循环，
 * 导致工具被 Spring 静默跳过。懒解析打破该环，运行时才取用循环实例。
 */
@Component
public class SubagentRunner {

    private final ObjectProvider<AgentLoopService> agentLoopServiceProvider;
    private final SessionService sessionService;
    private final SubagentRegistry registry;
    private final int maxDelegationDepth;

    public SubagentRunner(ObjectProvider<AgentLoopService> agentLoopServiceProvider,
                          SessionService sessionService,
                          SubagentRegistry registry,
                          @Value("${dsh.subagent.max-depth:3}") int maxDelegationDepth) {
        this.agentLoopServiceProvider = agentLoopServiceProvider;
        this.sessionService = sessionService;
        this.registry = registry;
        this.maxDelegationDepth = Math.max(1, maxDelegationDepth);
    }

    /** 委托结果。 */
    public record SubagentResult(String childId, String content, int depth) {
    }

    /**
     * 启动一个子代理并等待首轮结果。
     *
     * @param parentSessionId 父会话
     * @param prompt          委托任务
     * @param depth           当前委托深度（子代理深度 = depth + 1）
     * @param model           模型（可空，沿用默认）
     * @return 子代理 id + 结果
     * @throws DepthExceededException 超出深度上限
     */
    public SubagentResult start(SessionId parentSessionId, String prompt, int depth, String model) {
        if (depth >= maxDelegationDepth) {
            throw new DepthExceededException("委托深度超出上限: " + depth + " ≥ " + maxDelegationDepth);
        }
        Session parent = sessionService.getSession(parentSessionId);
        String childId = "sub_" + UUID.randomUUID().toString().substring(0, 8);
        Session childSession = sessionService.createSession("子代理-" + childId, model, parent.cwd());
        int childDepth = depth + 1;
        registry.register(parentSessionId, new SubagentHandle(childId, childSession.id(),
                childDepth, SubagentStatus.RUNNING, null, java.time.Instant.now()));

        AgentRunResult result = agentLoopService().run(AgentRunRequest.builder()
                .sessionId(childSession.id())
                .userMessage(prompt)
                .modelOverride(model)
                .executionId("sub-" + childId)
                .delegationDepth(childDepth)
                .build());

        SubagentHandle done = registry.get(parentSessionId, childId).withResult(
                SubagentStatus.DONE, result.content());
        registry.update(parentSessionId, childId, done);
        return new SubagentResult(childId, result.content(), childDepth);
    }

    /**
     * 向既有子代理发消息（同会话续轮，上下文保持）。
     */
    public Optional<String> followup(SessionId parentSessionId, String childId, String message) {
        SubagentHandle handle = registry.get(parentSessionId, childId);
        if (handle == null) {
            return Optional.empty();
        }
        AgentRunResult result = agentLoopService().run(AgentRunRequest.builder()
                .sessionId(handle.sessionId())
                .userMessage(message)
                .executionId("sub-" + childId)
                .delegationDepth(handle.delegationDepth())
                .build());
        registry.update(parentSessionId, childId, handle.withResult(SubagentStatus.DONE, result.content()));
        return Optional.of(result.content());
    }

    private AgentLoopService agentLoopService() {
        return agentLoopServiceProvider.getObject();
    }

    /** 深度超出上限。 */
    public static final class DepthExceededException extends RuntimeException {
        public DepthExceededException(String message) {
            super(message);
        }
    }
}
