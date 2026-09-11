package com.bizfty.anchon.dsh.api;

import com.bizfty.anchon.dsh.plantask.PlanTask;
import com.bizfty.anchon.dsh.plantask.PlanTaskQuestionService;
import com.bizfty.anchon.dsh.plantask.PlanTaskWorkerManager;
import com.bizfty.anchon.dsh.plantask.TaskRuntime;
import com.bizfty.anchon.dsh.plantask.TaskService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 计划任务 API — 批量提交 / 按工作区任务池 / 详情 / 运行态 / Worker 观测 / kill / 重跑 / 回收 / 待确认问题聚合。
 * <p>
 * 对应「工具 → 计划任务」工作台的后端端点；Worker 认领调度由
 * {@link PlanTaskWorkerManager} 常驻循环承担。
 */
@RestController
@RequestMapping("/api/plan-tasks")
public class PlanTaskController {

    private final TaskService taskService;
    private final PlanTaskWorkerManager workerManager;
    private final PlanTaskQuestionService questionService;

    public PlanTaskController(TaskService taskService,
                              PlanTaskWorkerManager workerManager,
                              PlanTaskQuestionService questionService) {
        this.taskService = taskService;
        this.workerManager = workerManager;
        this.questionService = questionService;
    }

    /** 批量提交多个任务（body: {workspaceId, tasks:[string...]}），全部入队并登记 Worker。 */
    @PostMapping("/batch")
    public ResponseEntity<?> submitBatch(@RequestBody Map<String, Object> body) {
        String workspaceId = (String) body.get("workspaceId");
        @SuppressWarnings("unchecked")
        List<String> tasks = (List<String>) body.get("tasks");
        try {
            List<PlanTask.PlanTaskView> created = taskService.submit(workspaceId, tasks).stream()
                    .map(PlanTask::view).toList();
            workerManager.register(workspaceId);
            return ResponseEntity.ok(Map.of("ok", true, "tasks", created));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 按工作区列出任务池（按提交顺序），视图含运行态计算字段 liveness/心跳/进度。
     */
    @GetMapping
    public List<Map<String, Object>> list(@RequestParam String workspaceId) {
        int inFlight = workerManager.inFlight(workspaceId);
        long now = System.currentTimeMillis();
        return taskService.list(workspaceId).stream()
                .map(t -> toViewMap(t, now, inFlight))
                .toList();
    }

    /** 任务详情。 */
    @GetMapping("/{taskId}")
    public ResponseEntity<?> get(@RequestParam String workspaceId, @PathVariable String taskId) {
        Optional<PlanTask> t = taskService.get(workspaceId, taskId);
        if (t.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(toViewMap(t.get(), System.currentTimeMillis(),
                workerManager.inFlight(workspaceId)));
    }

    /** 单任务运行态（存活判定 + 心跳 + 进度）。 */
    @GetMapping("/{taskId}/runtime")
    public ResponseEntity<?> runtime(@RequestParam String workspaceId, @PathVariable String taskId) {
        Optional<TaskRuntime> rt = taskService.runtime(workspaceId, taskId,
                workerManager.inFlight(workspaceId));
        return rt.<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** 工作区 Worker 运行态：并发上限、当前并发数、正在执行的 taskId。 */
    @GetMapping("/workers")
    public Map<String, Object> workers(@RequestParam String workspaceId) {
        return Map.of(
                "workspaceId", workspaceId,
                "concurrency", workerManager.concurrency(),
                "inFlight", workerManager.inFlight(workspaceId),
                "runningTaskIds", workerManager.runningTaskIds(workspaceId),
                "staleThresholdMs", taskService.staleThresholdMs(),
                "lostThresholdMs", taskService.lostThresholdMs());
    }

    /** 待确认问题聚合（某工作区所有任务 × 挂起问题）。 */
    @GetMapping("/questions/pending")
    public List<Map<String, Object>> pendingQuestions(@RequestParam String workspaceId) {
        return questionService.pendingByWorkspace(workspaceId).stream()
                .map(tq -> Map.<String, Object>of(
                        "task_id", tq.taskId(),
                        "workspace_id", tq.workspaceId(),
                        "prompt", tq.prompt(),
                        "question_id", tq.question().id(),
                        "question", tq.question().question(),
                        "options", tq.question().options(),
                        "multi_select", tq.question().multiSelect()))
                .toList();
    }

    /** 重跑（终态/失败任务回 queued，attempt+1）。 */
    @PostMapping("/{taskId}/rerun")
    public ResponseEntity<?> rerun(@RequestParam String workspaceId, @PathVariable String taskId) {
        Optional<PlanTask> t = taskService.rerun(workspaceId, taskId);
        if (t.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        workerManager.register(workspaceId);
        return ResponseEntity.ok(Map.of("ok", true, "task", t.get().view()));
    }

    /** 终止任务（best-effort：非终态任务置为 killed；运行中 agent 由会话取消机制兜底）。 */
    @PostMapping("/{taskId}/kill")
    public ResponseEntity<?> kill(@RequestParam String workspaceId, @PathVariable String taskId) {
        Optional<PlanTask> current = taskService.get(workspaceId, taskId);
        if (current.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        PlanTask task = current.get();
        if (task.terminal()) {
            return ResponseEntity.ok(Map.of("ok", true, "task", task.view()));
        }
        String from = task.status();
        Optional<PlanTask> killed = taskService.updateStatus(workspaceId, taskId, from,
                PlanTask.STATUS_KILLED, null, null, null, "任务已终止");
        return killed.map(t -> ResponseEntity.ok(Map.of("ok", true, "task", t.view())))
                .orElseGet(() -> ResponseEntity.ok(Map.of("ok", false, "error", "无法终止（状态已变化）")));
    }

    /**
     * 手动回收失联/疑似卡死任务 → queued（真运行任务会被拒绝）。
     */
    @PostMapping("/{taskId}/recover")
    public ResponseEntity<?> recover(@RequestParam String workspaceId, @PathVariable String taskId) {
        Optional<PlanTask> recovered = taskService.recover(workspaceId, taskId,
                workerManager.inFlight(workspaceId));
        if (recovered.isEmpty()) {
            Optional<PlanTask> current = taskService.get(workspaceId, taskId);
            if (current.isEmpty()) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(Map.of("ok", false,
                    "error", "任务仍存活或非活跃态，拒绝回收", "task", current.get().view()));
        }
        workerManager.register(workspaceId);
        return ResponseEntity.ok(Map.of("ok", true, "task", recovered.get().view()));
    }

    /** 组装任务视图 Map（含运行态计算字段）。 */
    private Map<String, Object> toViewMap(PlanTask t, long now, int inFlight) {
        TaskRuntime rt = TaskRuntime.of(t, now, taskService.staleThresholdMs(),
                taskService.lostThresholdMs(), inFlight);
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("id", t.id());
        m.put("workspaceId", t.workspaceId());
        m.put("prompt", t.prompt());
        m.put("status", t.status());
        m.put("claimedBy", t.claimedBy());
        m.put("executorSessionId", t.executorSessionId());
        m.put("createdAt", t.createdAt());
        m.put("claimedAt", t.claimedAt());
        m.put("finishedAt", t.finishedAt());
        m.put("result", t.result());
        m.put("error", t.error());
        m.put("attempt", t.attempt());
        m.put("lastHeartbeatAt", t.lastHeartbeatAt());
        m.put("lastActivityAt", t.lastActivityAt());
        m.put("activity", t.activity());
        m.put("liveness", rt.liveness());
        m.put("elapsedMs", rt.elapsedMs());
        m.put("heartbeatAgeMs", rt.heartbeatAgeMs());
        return m;
    }
}
