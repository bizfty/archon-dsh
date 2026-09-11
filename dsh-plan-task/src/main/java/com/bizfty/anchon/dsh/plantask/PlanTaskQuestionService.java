package com.bizfty.anchon.dsh.plantask;

import com.bizfty.anchon.dsh.interaction.InMemoryUserQuestionProvider;
import com.bizfty.anchon.dsh.interaction.UserQuestion;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 计划任务提问聚合 — 把某工作区运行中任务（可含挂起 ask_user_question）的待确认问题
 * 归并到任务维度，供前端「任务级问题清单 + 全局待确认汇总」使用。
 * <p>
 * 关联键：task.executorSessionId == pendingQuestion.sessionId（任务专属执行会话阻塞提问）。
 */
@Service
public class PlanTaskQuestionService {

    private final TaskService taskService;
    private final InMemoryUserQuestionProvider questionProvider;

    public PlanTaskQuestionService(TaskService taskService,
                                   InMemoryUserQuestionProvider questionProvider) {
        this.taskService = taskService;
        this.questionProvider = questionProvider;
    }

    /** 单条「任务 × 待确认问题」视图。 */
    public record TaskQuestion(String taskId, String workspaceId, String prompt,
                               UserQuestion question) {
    }

    /**
     * 聚合某工作区所有运行/挂起任务的待确认问题。
     * 返回按任务归并（同一任务多个问题按挂起顺序）。
     */
    public List<TaskQuestion> pendingByWorkspace(String workspaceId) {
        // sessionId → task 映射
        Map<String, PlanTask> sessionToTask = new java.util.HashMap<>();
        for (PlanTask t : taskService.list(workspaceId)) {
            if (t.executorSessionId() != null && !t.executorSessionId().isBlank()) {
                sessionToTask.put(t.executorSessionId(), t);
            }
        }
        if (sessionToTask.isEmpty()) {
            return List.of();
        }
        List<TaskQuestion> result = new ArrayList<>();
        for (UserQuestion q : questionProvider.pendingQuestions()) {
            if (q.sessionId() == null) {
                continue;
            }
            PlanTask task = sessionToTask.get(q.sessionId());
            if (task != null) {
                result.add(new TaskQuestion(task.id(), workspaceId, task.prompt(), q));
            }
        }
        return result;
    }

    /** 是否某任务存在待确认问题（供前端把 running 任务标记为「需确认」）。 */
    public boolean hasPending(String workspaceId, String taskId) {
        PlanTask task = taskService.get(workspaceId, taskId).orElse(null);
        if (task == null || task.executorSessionId() == null) {
            return false;
        }
        return questionProvider.pendingQuestions().stream()
                .anyMatch(q -> task.executorSessionId().equals(q.sessionId()));
    }
}
