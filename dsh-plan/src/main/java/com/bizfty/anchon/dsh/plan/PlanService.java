package com.bizfty.anchon.dsh.plan;

import com.bizfty.anchon.dsh.core.model.SessionId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * DAG 计划服务 — plan / plan_step / plan_step_dep 三表的关系型持久化与推进。
 * <p>
 * 语义对齐 goal-round-driver 的续行：计划支持依赖（DAG），
 * 只有"依赖已全部 completed"的 pending 步骤才可执行（nextSteps 拓扑计算）。
 * 创建/加边时做环检测（Kahn 拓扑排序），含环则拒绝。
 */
@Service
public class PlanService {

    private static final Logger log = LoggerFactory.getLogger(PlanService.class);

    /** 步骤状态词汇（todo=DAG 特殊形式统一词汇：执行中/完成/取消/出错/跳过）。 */
    public static final List<String> STEP_STATUSES =
            List.of("pending", "in_progress", "completed", "cancelled", "skipped", "failed");

    /** 终态（不再执行）：completed 正常完成；cancelled/skipped 有意跳过；failed 出错。 */
    public static final List<String> TERMINAL_STATUSES =
            List.of("completed", "cancelled", "skipped", "failed");

    /** 计划状态词汇。 */
    public static final List<String> PLAN_STATUSES =
            List.of("active", "completed", "abandoned");

    /** 非阻塞终态：cancelled/skipped 视为"跳过"，不阻塞下游依赖解锁。 */
    public static boolean isNonBlockingTerminal(String status) {
        return "cancelled".equals(status) || "skipped".equals(status);
    }

    /** 步骤工件类型（对齐 OpenSpec artifact 分型）：task=实现步骤；proposal/spec/design/doc=规划工件（需审阅门）。 */
    public static final List<String> STEP_KINDS =
            List.of("task", "proposal", "spec", "design", "doc");

    private final PlanRepository planRepository;
    private final PlanStepRepository stepRepository;
    private final PlanStepDepRepository depRepository;
    /** 可选：步骤执行关联表（无则不记录；测试/轻量装配可缺）。 */
    private final org.springframework.beans.factory.ObjectProvider<PlanStepExecutionRepository> executionRepoProvider;

    public PlanService(PlanRepository planRepository,
                          PlanStepRepository stepRepository,
                          PlanStepDepRepository depRepository) {
        this(planRepository, stepRepository, depRepository, null);
    }

    /** Spring 主构造：4 参（含可选的执行关联表仓库）。 */
    @Autowired
    public PlanService(PlanRepository planRepository,
                          PlanStepRepository stepRepository,
                          PlanStepDepRepository depRepository,
                          org.springframework.beans.factory.ObjectProvider<PlanStepExecutionRepository> executionRepoProvider) {
        this.planRepository = planRepository;
        this.stepRepository = stepRepository;
        this.depRepository = depRepository;
        this.executionRepoProvider = executionRepoProvider;
    }

    /** 创建 DAG 计划：头 + 步骤 + 依赖；含环则拒绝并回滚。 */
    @Transactional
    public PlanDetail createPlan(SessionId sessionId, String title,
                                 List<StepSpec> steps, List<DepSpec> deps) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("计划标题不能为空");
        }
        if (steps == null || steps.isEmpty()) {
            throw new IllegalArgumentException("计划至少需要一个步骤");
        }
        // 步骤 id 去重 + 非空标题 + 合法工件类型
        Set<String> ids = new HashSet<>();
        for (StepSpec spec : steps) {
            if (spec.title() == null || spec.title().isBlank()) {
                throw new IllegalArgumentException("步骤标题不能为空");
            }
            if (spec.kind() != null && !STEP_KINDS.contains(spec.kind())) {
                throw new IllegalArgumentException("未知步骤类型: " + spec.kind()
                        + "（可选: " + STEP_KINDS + "）");
            }
            if (!ids.add(spec.id())) {
                throw new IllegalArgumentException("步骤 id 重复: " + spec.id());
            }
        }
        List<DepSpec> normalizedDeps = normalizeDeps(deps, ids);
        validateAcyclic(ids, normalizedDeps);

        String planId = "plan-" + UUID.randomUUID().toString().substring(0, 8);
        Instant now = Instant.now();
        planRepository.save(new PlanEntity(planId, sessionId.value(), title.trim(),
                "active", now, now));

        Map<String, Integer> seqById = new HashMap<>();
        int seq = 0;
        for (StepSpec spec : steps) {
            stepRepository.save(new PlanStepEntity(spec.id(), planId, spec.title().trim(),
                    spec.description() == null ? "" : spec.description(),
                    "pending", spec.required(),
                    spec.kind() == null || spec.kind().isBlank() ? "task" : spec.kind(),
                    false, seq, now, now));
            seqById.put(spec.id(), seq);
            seq++;
        }
        for (DepSpec dep : normalizedDeps) {
            depRepository.save(new PlanStepDepEntity(dep.step(), dep.dependsOn()));
        }
        log.info("[Plan] session={} 创建计划 {} ({} 步骤, {} 依赖)", sessionId.value(),
                planId, steps.size(), normalizedDeps.size());
        return loadDetail(planId);
    }

    /** 当前会话的活动计划（无 → empty）。 */
    @Transactional(readOnly = true)
    public Optional<PlanDetail> currentPlan(SessionId sessionId) {
        return planRepository.findFirstBySessionIdOrderByUpdatedAtDesc(sessionId.value())
                .map(p -> loadDetail(p.getId()));
    }

    /** 按 id 读取完整计划（头 + 步骤 + 依赖）。 */
    @Transactional(readOnly = true)
    public PlanDetail loadDetail(String planId) {
        PlanEntity plan = planRepository.findById(planId)
                .orElseThrow(() -> new IllegalArgumentException("计划不存在: " + planId));
        List<PlanStepEntity> steps = stepRepository.findByPlanIdOrderBySeqAsc(planId);
        List<PlanStepDepEntity> deps = depRepository.findByStepIdIn(
                steps.stream().map(PlanStepEntity::getId).toList());
        return new PlanDetail(plan, steps, deps);
    }

    /** 更新步骤状态；非法状态拒绝；completed 后尝试推进计划整体状态。 */
    @Transactional
    public PlanDetail updateStepStatus(String planId, String stepId, String status) {
        if (!STEP_STATUSES.contains(status)) {
            throw new IllegalArgumentException("未知步骤状态: " + status + "（可选: " + STEP_STATUSES + "）");
        }
        PlanStepEntity step = stepRepository.findById(stepId)
                .orElseThrow(() -> new IllegalArgumentException("步骤不存在: " + stepId));
        if (!step.getPlanId().equals(planId)) {
            throw new IllegalArgumentException("步骤不属于该计划");
        }
        applyStepStatus(step, status);
        maybeCompletePlan(planId);
        return loadDetail(planId);
    }

    /**
     * 按 stepId 更新状态（供 todo 正向同步；todo 状态含 cancelled/skipped，
     * 而 plan_step 无此二态 → 映射：cancelled/skipped 视为 pending 复位）。
     */
    @Transactional
    public PlanDetail updateStepStatusByStepId(SessionId sessionId, String stepId, String todoStatus) {
        String planStatus = switch (todoStatus) {
            case "in_progress" -> "in_progress";
            case "completed" -> "completed";
            case "cancelled", "skipped" -> "pending";
            default -> "pending";
        };
        PlanStepEntity step = stepRepository.findById(stepId)
                .orElseThrow(() -> new IllegalArgumentException("步骤不存在: " + stepId));
        PlanEntity plan = planRepository.findById(step.getPlanId())
                .orElseThrow(() -> new IllegalArgumentException("计划不存在: " + step.getPlanId()));
        if (!plan.getSessionId().equals(sessionId.value())) {
            throw new IllegalArgumentException("步骤不属于该会话");
        }
        applyStepStatus(step, planStatus);
        maybeCompletePlan(step.getPlanId());
        return loadDetail(step.getPlanId());
    }

    /** 落库步骤状态。 */
    private void applyStepStatus(PlanStepEntity step, String status) {
        step.setStatus(status);
        step.setUpdatedAt(Instant.now());
        stepRepository.save(step);
    }

    /** 标记整个计划完成（全部步骤 completed）。 */
    @Transactional
    public PlanDetail completePlan(String planId) {
        List<PlanStepEntity> steps = stepRepository.findByPlanIdOrderBySeqAsc(planId);
        for (PlanStepEntity step : steps) {
            step.setStatus("completed");
            step.setUpdatedAt(Instant.now());
            stepRepository.save(step);
        }
        PlanEntity plan = planRepository.findById(planId)
                .orElseThrow(() -> new IllegalArgumentException("计划不存在: " + planId));
        plan.setStatus("completed");
        plan.setUpdatedAt(Instant.now());
        planRepository.save(plan);
        return loadDetail(planId);
    }

    /** 放弃计划（停止执行）。 */
    @Transactional
    public PlanDetail abandonPlan(String planId) {
        PlanEntity plan = planRepository.findById(planId)
                .orElseThrow(() -> new IllegalArgumentException("计划不存在: " + planId));
        plan.setStatus("abandoned");
        plan.setUpdatedAt(Instant.now());
        planRepository.save(plan);
        return loadDetail(planId);
    }

    /** 追加步骤（todo 整表替换用）。 */
    @Transactional
    public PlanDetail appendStep(String planId, String title, String description,
                                 String status, boolean required) {
        if (!STEP_STATUSES.contains(status)) {
            throw new IllegalArgumentException("未知步骤状态: " + status);
        }
        List<PlanStepEntity> steps = stepRepository.findByPlanIdOrderBySeqAsc(planId);
        int seq = steps.stream().mapToInt(PlanStepEntity::getSeq).max().orElse(-1) + 1;
        String stepId = "step-" + UUID.randomUUID().toString().substring(0, 8);
        Instant now = Instant.now();
        stepRepository.save(new PlanStepEntity(stepId, planId, title, description,
                status, required, seq, now, now));
        planRepository.findById(planId).ifPresent(plan -> {
            plan.setUpdatedAt(now);
            planRepository.save(plan);
        });
        return loadDetail(planId);
    }

    /** 更新步骤（title/description/status/required/seq 全量；todo 整表替换用）。 */
    @Transactional
    public PlanDetail updateStep(String planId, String stepId, String title, String description,
                                 String status, boolean required, int seq) {
        if (!STEP_STATUSES.contains(status)) {
            throw new IllegalArgumentException("未知步骤状态: " + status);
        }
        PlanStepEntity step = stepRepository.findById(stepId)
                .orElseThrow(() -> new IllegalArgumentException("步骤不存在: " + stepId));
        if (!step.getPlanId().equals(planId)) {
            throw new IllegalArgumentException("步骤不属于该计划");
        }
        step.setTitle(title);
        step.setDescription(description);
        step.setStatus(status);
        step.setRequired(required);
        step.setSeq(seq);
        step.setUpdatedAt(Instant.now());
        stepRepository.save(step);
        maybeCompletePlan(planId);
        return loadDetail(planId);
    }

    /** 删除步骤（含其依赖边）。 */
    @Transactional
    public PlanDetail deleteStep(String planId, String stepId) {
        stepRepository.findById(stepId).ifPresent(step -> {
            if (!step.getPlanId().equals(planId)) {
                throw new IllegalArgumentException("步骤不属于该计划");
            }
        });
        depRepository.deleteAll(depRepository.findByStepIdIn(List.of(stepId)));
        stepRepository.deleteById(stepId);
        return loadDetail(planId);
    }

    /**
     * 记录一次计划步骤的工具执行（写入 plan_step_execution 关联表）。
     * 无执行关联仓库（轻量装配）时静默跳过；planStepId 为空时不记录。
     */
    @Transactional
    public void recordExecution(String planId, String planStepId, SessionId sessionId,
                                String toolName, String argsSummary, String toolCallId, String status) {
        if (planStepId == null || planStepId.isBlank() || executionRepoProvider == null) {
            return;
        }
        PlanStepExecutionRepository repo = executionRepoProvider.getIfAvailable();
        if (repo == null) {
            return;
        }
        String id = "pse-" + UUID.randomUUID().toString().substring(0, 8);
        repo.save(new PlanStepExecutionEntity(id, planId, planStepId, sessionId.value(),
                toolName, argsSummary == null ? "" : argsSummary, toolCallId, status, Instant.now()));
    }

    /** 某计划步骤的全部执行记录（按时间升序；并行执行各调用独立一行）。 */
    @Transactional(readOnly = true)
    public List<PlanStepExecutionEntity> listExecutions(String planStepId) {
        if (executionRepoProvider == null) {
            return List.of();
        }
        PlanStepExecutionRepository repo = executionRepoProvider.getIfAvailable();
        return repo == null ? List.of() : repo.findByPlanStepIdOrderByCreatedAtAsc(planStepId);
    }

    /**
     * 下一步可执行步骤（DAG 推进语义）：
     * <ul>
     *   <li>排除终态步骤（completed/cancelled/skipped/failed）；</li>
     *   <li>依赖全部满足才可执行：completed 满足；cancelled/skipped 视为"跳过"
     *       也满足（不阻塞下游）；failed 阻塞（下游不可执行）；</li>
     *   <li>审阅门：doc 类步骤（proposal/spec/design/doc）须 reviewed=true 才可执行；
     *       task 类实现步骤不受审阅门约束（旧计划零影响）；</li>
     *   <li>按 seq 稳定排序。</li>
     * </ul>
     */
    @Transactional(readOnly = true)
    public List<PlanStepEntity> nextSteps(String planId) {
        PlanDetail detail = loadDetail(planId);
        Map<String, PlanStepEntity> byId = new HashMap<>();
        for (PlanStepEntity s : detail.steps()) {
            byId.put(s.getId(), s);
        }
        Map<String, Set<String>> depsByStep = new HashMap<>();
        for (PlanStepDepEntity dep : detail.deps()) {
            depsByStep.computeIfAbsent(dep.getStepId(), k -> new HashSet<>()).add(dep.getDependsOnStepId());
        }
        return detail.steps().stream()
                .filter(s -> !TERMINAL_STATUSES.contains(s.getStatus()))
                .filter(s -> "task".equals(s.getKind()) || s.isReviewed())
                .filter(s -> {
                    Set<String> deps = depsByStep.getOrDefault(s.getId(), Set.of());
                    for (String depId : deps) {
                        PlanStepEntity dep = byId.get(depId);
                        if (dep == null) {
                            continue;
                        }
                        if ("completed".equals(dep.getStatus())
                                || isNonBlockingTerminal(dep.getStatus())) {
                            continue; // 完成或跳过 → 满足
                        }
                        return false; // pending/in_progress/failed → 未满足
                    }
                    return true;
                })
                .toList();
    }

    /**
     * 审阅步骤（批准/撤回批准）：doc 类步骤 reviewed=true 后才进入 nextSteps。
     * task 类步骤允许设置（无实际门禁作用，保持模型统一）。
     */
    @Transactional
    public PlanDetail reviewStep(String planId, String stepId, boolean reviewed) {
        PlanStepEntity step = stepRepository.findById(stepId)
                .orElseThrow(() -> new IllegalArgumentException("步骤不存在: " + stepId));
        if (!step.getPlanId().equals(planId)) {
            throw new IllegalArgumentException("步骤不属于该计划");
        }
        step.setReviewed(reviewed);
        step.setUpdatedAt(Instant.now());
        stepRepository.save(step);
        log.info("[Plan] {} 步骤 {} 审阅状态 → {}", planId, stepId, reviewed ? "approved" : "withdrawn");
        return loadDetail(planId);
    }

    /** 会话是否存在活动计划。 */
    @Transactional(readOnly = true)
    public boolean hasActivePlan(SessionId sessionId) {
        return planRepository.findFirstBySessionIdOrderByUpdatedAtDesc(sessionId.value())
                .map(p -> "active".equals(p.getStatus()))
                .orElse(false);
    }

    // ---- 内部 ----

    /** 依赖归一化：去重、丢弃自依赖、校验引用的步骤存在。 */
    private List<DepSpec> normalizeDeps(List<DepSpec> deps, Set<String> stepIds) {
        if (deps == null) {
            return List.of();
        }
        Set<String> seen = new HashSet<>();
        List<DepSpec> result = new ArrayList<>();
        for (DepSpec dep : deps) {
            if (dep == null || dep.step() == null || dep.dependsOn() == null) {
                continue;
            }
            if (dep.step().equals(dep.dependsOn())) {
                continue; // 自依赖无意义，忽略
            }
            if (!stepIds.contains(dep.step()) || !stepIds.contains(dep.dependsOn())) {
                throw new IllegalArgumentException("依赖引用了不存在的步骤: "
                        + dep.step() + " -> " + dep.dependsOn());
            }
            String key = dep.step() + "->" + dep.dependsOn();
            if (seen.add(key)) {
                result.add(dep);
            }
        }
        return result;
    }

    /** Kahn 拓扑排序环检测：返回 false = 含环。 */
    private void validateAcyclic(Set<String> stepIds, List<DepSpec> deps) {
        Map<String, Set<String>> adj = new HashMap<>();
        Map<String, Integer> indegree = new HashMap<>();
        for (String id : stepIds) {
            adj.put(id, new HashSet<>());
            indegree.put(id, 0);
        }
        for (DepSpec dep : deps) {
            adj.get(dep.dependsOn()).add(dep.step()); // dependsOn -> step
            indegree.merge(dep.step(), 1, Integer::sum);
        }
        Deque<String> queue = new ArrayDeque<>();
        for (Map.Entry<String, Integer> e : indegree.entrySet()) {
            if (e.getValue() == 0) {
                queue.add(e.getKey());
            }
        }
        int visited = 0;
        while (!queue.isEmpty()) {
            String node = queue.poll();
            visited++;
            for (String next : adj.get(node)) {
                int deg = indegree.merge(next, -1, Integer::sum);
                if (deg == 0) {
                    queue.add(next);
                }
            }
        }
        if (visited != stepIds.size()) {
            throw new IllegalArgumentException("计划存在循环依赖（DAG 校验失败）");
        }
    }

    /**
     * 全部必做步骤（required）进入终态 → 计划置 completed。
     * 非 required 步骤可被 cancelled/skipped 而不阻塞完成；
     * 任一 required 步骤 failed → 计划保持 active（由模型决定阻塞或重试）。
     */
    private void maybeCompletePlan(String planId) {
        List<PlanStepEntity> steps = stepRepository.findByPlanIdOrderBySeqAsc(planId);
        if (steps.isEmpty()) {
            return;
        }
        boolean allRequiredDone = steps.stream()
                .filter(PlanStepEntity::isRequired)
                .allMatch(s -> TERMINAL_STATUSES.contains(s.getStatus()));
        if (allRequiredDone) {
            planRepository.findById(planId).ifPresent(plan -> {
                plan.setStatus("completed");
                plan.setUpdatedAt(Instant.now());
                planRepository.save(plan);
            });
        }
    }

    // ---- 模型 / 传输结构 ----

    /** 步骤创建规格：required 表示必完成（默认 true）；kind 为工件类型（默认 task）。 */
    public record StepSpec(String id, String title, String description, boolean required, String kind) {

        public StepSpec(String id, String title, String description) {
            this(id, title, description, true, "task");
        }

        public StepSpec(String id, String title, String description, boolean required) {
            this(id, title, description, required, "task");
        }
    }

    /** 依赖创建规格：step 依赖 dependsOn（后者先完成）。 */
    public record DepSpec(String step, String dependsOn) {
    }

    /** 完整计划详情（头 + 步骤 + 依赖 + nextSteps）。 */
    public record PlanDetail(
            PlanEntity plan,
            List<PlanStepEntity> steps,
            List<PlanStepDepEntity> deps,
            List<PlanStepEntity> nextSteps) {

        public PlanDetail(PlanEntity plan, List<PlanStepEntity> steps, List<PlanStepDepEntity> deps) {
            this(plan, steps, deps, null);
        }

        public PlanDetail withNextSteps(List<PlanStepEntity> next) {
            return new PlanDetail(plan, steps, deps, next);
        }
    }

    /** 步骤状态更新后的完整详情（含 nextSteps）。 */
    public Map<String, Object> toApiView(PlanDetail detail) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("plan", planView(detail.plan()));
        view.put("steps", detail.steps().stream().map(this::stepView).toList());
        view.put("deps", detail.deps().stream()
                .map(d -> Map.of("step", d.getStepId(), "dependsOn", d.getDependsOnStepId()))
                .toList());
        List<PlanStepEntity> next = detail.nextSteps() != null
                ? detail.nextSteps()
                : nextSteps(detail.plan().getId());
        view.put("nextSteps", next.stream().map(this::stepView).toList());
        return view;
    }

    private Map<String, Object> planView(PlanEntity plan) {
        return Map.of(
                "id", plan.getId(),
                "sessionId", plan.getSessionId(),
                "title", plan.getTitle(),
                "status", plan.getStatus(),
                "createdAt", plan.getCreatedAt().toString(),
                "updatedAt", plan.getUpdatedAt().toString());
    }

    private Map<String, Object> stepView(PlanStepEntity step) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", step.getId());
        m.put("planId", step.getPlanId());
        m.put("title", step.getTitle());
        m.put("description", step.getDescription());
        m.put("status", step.getStatus());
        m.put("required", step.isRequired());
        m.put("kind", step.getKind());
        m.put("reviewed", step.isReviewed());
        m.put("seq", step.getSeq());
        return m;
    }

    /** 供目标续行注入：下一步步骤标题（无 → empty）。 */
    public String nextStepsText(String planId) {
        List<PlanStepEntity> next = nextSteps(planId);
        if (next.isEmpty()) {
            return "";
        }
        return next.stream()
                .map(s -> "- " + s.getTitle())
                .collect(Collectors.joining("\n"));
    }
}
