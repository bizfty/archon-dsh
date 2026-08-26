package com.bizfty.anchon.dsh.plan;

import com.bizfty.anchon.dsh.core.model.SessionId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DAG 计划服务测试（H2 内存）：创建/环检测/拓扑 next-steps/状态推进/完成。
 */
@SpringBootTest(classes = PlanServiceTest.TestConfig.class)
class PlanServiceTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EnableJpaRepositories(basePackageClasses = PlanRepository.class)
    @EntityScan(basePackageClasses = PlanEntity.class)
    static class TestConfig {
    }

    @org.springframework.beans.factory.annotation.Autowired
    private PlanRepository planRepository;
    @org.springframework.beans.factory.annotation.Autowired
    private PlanStepRepository stepRepository;
    @org.springframework.beans.factory.annotation.Autowired
    private PlanStepDepRepository depRepository;
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.beans.factory.ObjectProvider<PlanStepExecutionRepository> executionRepoProvider;

    private PlanService service;

    @BeforeEach
    void setUp() {
        planRepository.deleteAll();
        stepRepository.deleteAll();
        depRepository.deleteAll();
        PlanStepExecutionRepository er = executionRepoProvider.getIfAvailable();
        if (er != null) {
            er.deleteAll();
        }
        service = new PlanService(planRepository, stepRepository, depRepository, executionRepoProvider);
    }

    private SessionId sid(String s) {
        return SessionId.of(s);
    }

    @Test
    void createPlanWithDependencies() {
        PlanService.PlanDetail detail = service.createPlan(sid("s1"), "发布流程",
                List.of(
                        new PlanService.StepSpec("s1", "构建", "mvn package"),
                        new PlanService.StepSpec("s2", "测试", "mvn test"),
                        new PlanService.StepSpec("s3", "部署", "deploy")),
                List.of(
                        new PlanService.DepSpec("s2", "s1"),
                        new PlanService.DepSpec("s3", "s2")));

        assertEquals("active", detail.plan().getStatus());
        assertEquals(3, detail.steps().size());
        assertEquals(2, detail.deps().size());
        // 初始可执行：仅 s1（无依赖）
        List<PlanStepEntity> next = service.nextSteps(detail.plan().getId());
        assertEquals(1, next.size());
        assertEquals("s1", next.get(0).getId());
    }

    @Test
    void rejectsCycle() {
        assertThrows(IllegalArgumentException.class, () -> service.createPlan(sid("s2"), "环",
                List.of(
                        new PlanService.StepSpec("a", "A", ""),
                        new PlanService.StepSpec("b", "B", "")),
                List.of(
                        new PlanService.DepSpec("a", "b"),
                        new PlanService.DepSpec("b", "a"))));
    }

    @Test
    void rejectsUnknownStepInDependency() {
        assertThrows(IllegalArgumentException.class, () -> service.createPlan(sid("s3"), "坏依赖",
                List.of(new PlanService.StepSpec("a", "A", "")),
                List.of(new PlanService.DepSpec("a", "nope"))));
    }

    @Test
    void nextStepsUnlockAsDependenciesComplete() {
        PlanService.PlanDetail detail = service.createPlan(sid("s4"), "流水线",
                List.of(
                        new PlanService.StepSpec("p1", "准备", ""),
                        new PlanService.StepSpec("p2", "编译", ""),
                        new PlanService.StepSpec("p3", "发布", "")),
                List.of(
                        new PlanService.DepSpec("p2", "p1"),
                        new PlanService.DepSpec("p3", "p2")));
        String planId = detail.plan().getId();

        // p1 done → p2 可执行
        service.updateStepStatus(planId, "p1", "completed");
        List<PlanStepEntity> next1 = service.nextSteps(planId);
        assertEquals(List.of("p2"), next1.stream().map(PlanStepEntity::getId).toList());

        // p2 done → p3 可执行
        service.updateStepStatus(planId, "p2", "completed");
        assertEquals(List.of("p3"),
                service.nextSteps(planId).stream().map(PlanStepEntity::getId).toList());

        // p3 done → 全部完成，计划自动 completed
        service.updateStepStatus(planId, "p3", "completed");
        PlanService.PlanDetail after = service.loadDetail(planId);
        assertEquals("completed", after.plan().getStatus());
        assertTrue(service.nextSteps(planId).isEmpty());
    }

    @Test
    void parallelDependenciesAllMustComplete() {
        PlanService.PlanDetail detail = service.createPlan(sid("s5"), "并行",
                List.of(
                        new PlanService.StepSpec("x", "X", ""),
                        new PlanService.StepSpec("y", "Y", ""),
                        new PlanService.StepSpec("z", "Z", "")),
                List.of(
                        new PlanService.DepSpec("z", "x"),
                        new PlanService.DepSpec("z", "y")));
        String planId = detail.plan().getId();

        // 初始可执行 x 和 y（并行）
        assertEquals(2, service.nextSteps(planId).size());
        // x 完成还不够，y 未完成 z 不可执行
        service.updateStepStatus(planId, "x", "completed");
        assertTrue(service.nextSteps(planId).stream().noneMatch(s -> "z".equals(s.getId())));
        service.updateStepStatus(planId, "y", "completed");
        assertEquals(List.of("z"),
                service.nextSteps(planId).stream().map(PlanStepEntity::getId).toList());
    }

    @Test
    void rejectUnknownStepStatus() {
        PlanService.PlanDetail detail = service.createPlan(sid("s6"), "状态",
                List.of(new PlanService.StepSpec("a", "A", "")), List.of());
        assertThrows(IllegalArgumentException.class,
                () -> service.updateStepStatus(detail.plan().getId(), "a", "exploded"));
    }

    @Test
    void abandonPlanStopsExecution() {
        PlanService.PlanDetail detail = service.createPlan(sid("s7"), "放弃",
                List.of(new PlanService.StepSpec("a", "A", "")), List.of());
        PlanService.PlanDetail abandoned = service.abandonPlan(detail.plan().getId());
        assertEquals("abandoned", abandoned.plan().getStatus());
        assertFalse(service.hasActivePlan(sid("s7")));
    }

    @Test
    void currentPlanReturnsLatestActive() {
        service.createPlan(sid("s8"), "第一个",
                List.of(new PlanService.StepSpec("a", "A", "")), List.of());
        service.createPlan(sid("s8"), "第二个",
                List.of(new PlanService.StepSpec("b", "B", "")), List.of());
        assertTrue(service.currentPlan(sid("s8")).isPresent());
        assertEquals("第二个", service.currentPlan(sid("s8")).get().plan().getTitle());
    }

    @Test
    void skippedDependencyDoesNotBlockDownstream() {
        // z 依赖 x,y；x 被 skipped（非阻塞终态）→ z 仍可执行（y 完成即可）
        PlanService.PlanDetail detail = service.createPlan(sid("s9"), "跳过",
                List.of(
                        new PlanService.StepSpec("x", "X", ""),
                        new PlanService.StepSpec("y", "Y", ""),
                        new PlanService.StepSpec("z", "Z", "")),
                List.of(
                        new PlanService.DepSpec("z", "x"),
                        new PlanService.DepSpec("z", "y")));
        String planId = detail.plan().getId();
        service.updateStepStatus(planId, "x", "skipped");
        service.updateStepStatus(planId, "y", "completed");
        assertTrue(service.nextSteps(planId).stream().anyMatch(s -> "z".equals(s.getId())),
                "skipped 依赖不阻塞下游");
    }

    @Test
    void failedDependencyBlocksDownstream() {
        PlanService.PlanDetail detail = service.createPlan(sid("s10"), "出错",
                List.of(
                        new PlanService.StepSpec("x", "X", ""),
                        new PlanService.StepSpec("z", "Z", "")),
                List.of(new PlanService.DepSpec("z", "x")));
        String planId = detail.plan().getId();
        service.updateStepStatus(planId, "x", "failed");
        assertTrue(service.nextSteps(planId).stream().noneMatch(s -> "z".equals(s.getId())),
                "failed 依赖阻塞下游");
    }

    @Test
    void nonRequiredStepsDoNotBlockPlanCompletion() {
        // required=false 的步骤 skipped → 计划仍可 completed（仅 required 步骤判定）
        PlanService.PlanDetail detail = service.createPlan(sid("s11"), "可选完成",
                List.of(
                        new PlanService.StepSpec("must", "必做", "", true),
                        new PlanService.StepSpec("opt", "可选", "", false)),
                List.of());
        String planId = detail.plan().getId();
        service.updateStepStatus(planId, "opt", "skipped");
        service.updateStepStatus(planId, "must", "completed");
        assertEquals("completed", service.loadDetail(planId).plan().getStatus(),
                "必做项完成 + 可选项跳过 → 计划完成");
    }

    @Test
    void cancelledIsTerminalAndNotNext() {
        PlanService.PlanDetail detail = service.createPlan(sid("s12"), "取消",
                List.of(new PlanService.StepSpec("a", "A", "")), List.of());
        String planId = detail.plan().getId();
        service.updateStepStatus(planId, "a", "cancelled");
        assertTrue(service.nextSteps(planId).isEmpty(), "cancelled 步骤不再可执行");
    }

    // ---- 步骤执行关联表（plan_step_execution） ----

    @Test
    void recordExecutionWritesAndListReturnsAscending() {
        PlanService.PlanDetail detail = service.createPlan(sid("s13"), "执行关联",
                List.of(new PlanService.StepSpec("a", "A", "")), List.of());
        String planId = detail.plan().getId();

        service.recordExecution(planId, "a", sid("s13"), "plan_get", "{\"plan_id\":\"p\"}", "call-1", "ok");
        sleep(2);
        service.recordExecution(planId, "a", sid("s13"), "read_file", "{\"path\":\"/tmp/x\"}", "call-2", "ok");
        sleep(2);
        service.recordExecution(planId, "a", sid("s13"), "run_bash", "{\"cmd\":\"ls\"}", "call-3", "failed");

        List<PlanStepExecutionEntity> rows = service.listExecutions("a");
        assertEquals(3, rows.size());
        // 时间升序：call-1 → call-2 → call-3
        assertEquals("call-1", rows.get(0).getToolCallId());
        assertEquals("call-2", rows.get(1).getToolCallId());
        assertEquals("call-3", rows.get(2).getToolCallId());
        // 关联字段完整
        PlanStepExecutionEntity first = rows.get(0);
        assertEquals(planId, first.getPlanId());
        assertEquals("a", first.getPlanStepId());
        assertEquals("s13", first.getSessionId());
        assertEquals("plan_get", first.getToolName());
        assertTrue(first.getArgsSummary().contains("plan_id"));
        assertEquals("ok", first.getStatus());
    }

    @Test
    void recordExecutionSkipsNullPlanStepId() {
        // 无 planStepId（普通聊天）→ 不写关联行
        PlanService.PlanDetail detail = service.createPlan(sid("s14"), "无关联",
                List.of(new PlanService.StepSpec("a", "A", "")), List.of());
        service.recordExecution(detail.plan().getId(), null, sid("s14"), "run_bash", "{}", "call-0", "ok");
        service.recordExecution(detail.plan().getId(), "", sid("s14"), "run_bash", "{}", "call-0", "ok");
        assertTrue(service.listExecutions("a").isEmpty(), "planStepId 为空时不写关联行");
    }

    @Test
    void listExecutionsEmptyForUnknownStep() {
        assertTrue(service.listExecutions("no-such-step").isEmpty());
    }

    // ---- 审阅门（kind + reviewed）----

    @Test
    void unreviewedDocStepNotInNextSteps() {
        // spec 工件步骤（未批准）不出现在 nextSteps；task 步骤不受审阅门约束
        PlanService.PlanDetail detail = service.createPlan(sid("s15"), "审阅门",
                List.of(
                        new PlanService.StepSpec("doc", "导出契约", "契约内容", true, "spec"),
                        new PlanService.StepSpec("impl", "实现导出", "实现步骤")),
                List.of());
        List<PlanStepEntity> next = service.nextSteps(detail.plan().getId());
        assertTrue(next.stream().anyMatch(s -> "impl".equals(s.getId())), "task 步骤无需审阅即可执行");
        assertTrue(next.stream().noneMatch(s -> "doc".equals(s.getId())), "未批准的 doc 步骤不可执行");

        // 批准后进入 nextSteps
        service.reviewStep(detail.plan().getId(), "doc", true);
        List<PlanStepEntity> next2 = service.nextSteps(detail.plan().getId());
        assertTrue(next2.stream().anyMatch(s -> "doc".equals(s.getId())), "批准后 doc 步骤可执行");
        // reviewed 状态已落库
        PlanStepEntity doc = service.loadDetail(detail.plan().getId()).steps().stream()
                .filter(s -> "doc".equals(s.getId())).findFirst().orElseThrow();
        assertTrue(doc.isReviewed());
        assertEquals("spec", doc.getKind());
    }

    @Test
    void withdrawReviewReGatesDocStep() {
        PlanService.PlanDetail detail = service.createPlan(sid("s16"), "撤回批准",
                List.of(new PlanService.StepSpec("d", "设计", "", true, "design")), List.of());
        service.reviewStep(detail.plan().getId(), "d", true);
        assertTrue(service.nextSteps(detail.plan().getId()).stream().anyMatch(s -> "d".equals(s.getId())));
        service.reviewStep(detail.plan().getId(), "d", false);
        assertTrue(service.nextSteps(detail.plan().getId()).stream().noneMatch(s -> "d".equals(s.getId())),
                "撤回批准后 doc 步骤不再可执行");
    }

    @Test
    void rejectUnknownKind() {
        assertThrows(IllegalArgumentException.class, () -> service.createPlan(sid("s17"), "坏类型",
                List.of(new PlanService.StepSpec("a", "A", "", true, "exploded")), List.of()));
    }

    @Test
    void reviewUnknownStepRejected() {
        PlanService.PlanDetail detail = service.createPlan(sid("s18"), "坏审阅",
                List.of(new PlanService.StepSpec("a", "A", "")), List.of());
        assertThrows(IllegalArgumentException.class,
                () -> service.reviewStep(detail.plan().getId(), "nope", true));
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
