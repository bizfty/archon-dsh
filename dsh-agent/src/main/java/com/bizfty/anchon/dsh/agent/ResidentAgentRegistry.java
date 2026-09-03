package com.bizfty.anchon.dsh.agent;

import com.bizfty.anchon.dsh.core.event.SessionEventBus;
import com.bizfty.anchon.dsh.core.event.SessionEventType;
import com.bizfty.anchon.dsh.core.model.SessionId;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 常驻 agent 注册表（M4，design-resident-agent.md §4.1）：sessionId → {@link ResidentAgent}。
 * 等价上游 {@code restoreOrCreateConfigured}：同一会话固定同一执行对象（身份/队列/状态）。
 * 单实例内存队列（D6 定案）；{@link #release} 供会话删除/长期不活跃回收。
 * <p>
 * M4-5：装配 {@link SessionEventBus} 后，每个执行者的非 IDLE phase 迁移自动发布
 * {@code AGENT_PHASE} 事件（payload={phase, executionId}，经持久化监听器落 anchon_session_event，
 * 供 RestoreAgentRunner 启动恢复）。
 */
@Component
public class ResidentAgentRegistry {

    private final Map<SessionId, ResidentAgent> agents = new ConcurrentHashMap<>();
    private final SessionEventBus eventBus;

    /** 无事件总线构造（单测/无装配）：phase 迁移不发布事件。 */
    public ResidentAgentRegistry() {
        this(null);
    }

    public ResidentAgentRegistry(SessionEventBus eventBus) {
        this.eventBus = eventBus;
    }

    /** 取或建该会话的执行对象（线程安全）；创建时挂 phase → AGENT_PHASE 事件发布。 */
    public ResidentAgent agent(SessionId sessionId) {
        return agents.computeIfAbsent(sessionId, sid -> {
            ResidentAgent agent = new ResidentAgent(sid);
            if (eventBus != null) {
                agent.setPhaseListener(phase -> eventBus.publish(sid, SessionEventType.AGENT_PHASE,
                        Map.of("phase", phase.name().toLowerCase(java.util.Locale.ROOT),
                                "executionId", agent.residentState().executionId())));
            }
            return agent;
        });
    }

    /**
     * 对象态取消（M4-3，design §4.2）：存在则置位协作取消标志（phase→aborted 当无排队任务）；
     * 不存在（无执行/空闲）→ no-op false。不创建对象——空闲会话无对象可取消。
     */
    public boolean abort(SessionId sessionId) {
        ResidentAgent agent = agents.get(sessionId);
        if (agent == null) {
            return false;
        }
        agent.abort();
        return true;
    }

    /** 该会话是否有取消请求（对象不存在 → false；只读不创建）。 */
    public boolean abortRequested(SessionId sessionId) {
        ResidentAgent agent = agents.get(sessionId);
        return agent != null && agent.isAbortRequested();
    }

    /** 清除该会话取消标志（对象不存在 → no-op；每个新 turn 开始时调用）。 */
    public void resetAbort(SessionId sessionId) {
        ResidentAgent agent = agents.get(sessionId);
        if (agent != null) {
            agent.resetAbort();
        }
    }

    /** 记录该会话执行身份（M4-4：executeInner 确定 executionId/series 后调用；对象不存在则跳过）。 */
    public void recordIdentity(SessionId sessionId, String executionId, String agentId,
                               String seriesId, String headerFingerprint) {
        ResidentAgent agent = agents.get(sessionId);
        if (agent != null) {
            agent.recordIdentity(executionId, agentId, seriesId, headerFingerprint);
        }
    }

    /**
     * resume API（M4-4，design §4.3）：会话处于 aborted（被取消停驻）且已有执行身份时
     * 返回该身份（同 executionId 续轮 / 供 AGENT_PHASE 事件与重启恢复引用）；否则空。
     * 只读——不自动重发用户消息（防 fact 重复 seq，红线 §6-4c）；续轮由上层用返回的
     * executionId 发起新 run。
     */
    public java.util.Optional<ResidentAgent.ResidentState> resume(SessionId sessionId) {
        ResidentAgent agent = agents.get(sessionId);
        return agent == null ? java.util.Optional.empty() : agent.abortedResumeState();
    }

    /** 显式释放：关闭执行者并从注册表移除（会话删除 / idle 回收钩子调用）。 */
    public void release(SessionId sessionId) {
        ResidentAgent agent = agents.remove(sessionId);
        if (agent != null) {
            agent.close();
        }
    }

    /** 当前存活执行对象快照（供 phase 巡检 / m4-5 重启恢复前清理）。 */
    public Map<SessionId, ResidentAgent> agents() {
        return Map.copyOf(agents);
    }
}
