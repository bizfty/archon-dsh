package com.bizfty.anchon.dsh.plantask;

import com.bizfty.anchon.dsh.agent.AgentLoopService;
import com.bizfty.anchon.dsh.agent.AgentRunRequest;
import com.bizfty.anchon.dsh.agent.AgentRunResult;
import com.bizfty.anchon.dsh.core.model.SessionId;
import com.bizfty.anchon.dsh.session.SessionService;
import com.bizfty.anchon.dsh.session.WorkspaceService;
import com.bizfty.anchon.dsh.tool.ToolEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 默认计划任务执行器 — 每个任务开一个独立持久会话跑 Agent（对应 DSH plan-task 的执行者）。
 * <p>
 * 流程：为任务创建独立会话（记录 executorSessionId → 供任务级提问关联/应答）→
 * AgentLoopService.stream 驱动该会话执行 prompt（可能因 ask_user_question 阻塞等待用户应答，
 * 应答后在同一虚拟线程继续）→ 返回结果。
 * <p>
 * 进度（L1）：通过 onToolEvent 回调把「第 N 步 · 工具名」写回任务 activity，
 * 供前端展示当前动作；同时刷新 lastActivityAt。
 * <p>
 * AgentLoopService 用 ObjectProvider 懒解析（与 SubagentRunner 同理），避免
 * ToolRegistry→...→AgentLoopService→ToolRegistry 循环造成工具被静默跳过。
 */
@Component
public class DefaultPlanTaskExecutor implements PlanTaskExecutor {

    private static final Logger log = LoggerFactory.getLogger(DefaultPlanTaskExecutor.class);

    private final TaskService taskService;
    private final SessionService sessionService;
    private final WorkspaceService workspaceService;
    private final ObjectProvider<AgentLoopService> agentLoopServiceProvider;

    public DefaultPlanTaskExecutor(TaskService taskService,
                                   SessionService sessionService,
                                   WorkspaceService workspaceService,
                                   ObjectProvider<AgentLoopService> agentLoopServiceProvider) {
        this.taskService = taskService;
        this.sessionService = sessionService;
        this.workspaceService = workspaceService;
        this.agentLoopServiceProvider = agentLoopServiceProvider;
    }

    @Override
    public ExecutionOutcome execute(String workspaceId, String taskId, String prompt) {
        // 工作区目录作为任务会话 cwd
        String cwd = null;
        try {
            var ws = workspaceService.get(com.bizfty.anchon.dsh.core.model.WorkspaceId.of(workspaceId));
            cwd = ws.path();
        } catch (Exception e) {
            log.debug("[PlanTask] 工作区 {} 未找到或路径缺失，使用默认 cwd", workspaceId);
        }
        // 创建任务专属会话并绑定到任务（供提问关联）
        var session = sessionService.createSession("计划任务-" + taskId, null, cwd);
        String sessionId = session.id().value();
        taskService.bindExecutorSession(workspaceId, taskId, sessionId);
        taskService.reportActivity(workspaceId, taskId, "已启动，正在思考");
        log.info("[PlanTask] 任务 {} 启动执行会话 {}", taskId, sessionId);

        AtomicInteger step = new AtomicInteger();
        try {
            AgentRunResult result = agentLoopServiceProvider.getObject().stream(
                    AgentRunRequest.builder()
                            .sessionId(SessionId.of(sessionId))
                            .userMessage(prompt)
                            .executionId("plan-task-" + taskId)
                            .delegationDepth(0)
                            .build(),
                    token -> { /* token 流不用于任务进度，忽略 */ },
                    (ToolEvent ev) -> onToolEvent(workspaceId, taskId, step, ev));
            taskService.reportActivity(workspaceId, taskId,
                    "执行完成（步数 " + result.steps() + "，工具调用 " + result.toolCalls() + "）");
            String content = result.content();
            return ExecutionOutcome.done(content == null ? "" : content);
        } catch (Exception e) {
            log.error("[PlanTask] 任务 {} 执行失败", taskId, e);
            return ExecutionOutcome.failed(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    /** 工具事件 → 任务动作摘要（进度 L1）。 */
    private void onToolEvent(String workspaceId, String taskId, AtomicInteger step, ToolEvent ev) {
        try {
            String tool = ev.toolName() == null ? "工具" : ev.toolName();
            int n = step.incrementAndGet();
            String summary = "第 " + n + " 步 · " + tool;
            if (ev.message() != null && !ev.message().isBlank()) {
                String msg = ev.message().length() > 80 ? ev.message().substring(0, 80) + "…" : ev.message();
                summary = summary + "：" + msg;
            }
            taskService.reportActivity(workspaceId, taskId, summary);
        } catch (Exception e) {
            log.debug("[PlanTask] 任务 {} 进度回写失败: {}", taskId, e.getMessage());
        }
    }
}
