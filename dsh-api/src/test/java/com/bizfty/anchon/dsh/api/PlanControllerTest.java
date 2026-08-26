package com.bizfty.anchon.dsh.api;

import com.bizfty.anchon.dsh.plan.PlanService;
import com.bizfty.anchon.dsh.plan.PlanEntity;
import com.bizfty.anchon.dsh.plan.PlanRepository;
import com.bizfty.anchon.dsh.plan.PlanStepDepRepository;
import com.bizfty.anchon.dsh.plan.PlanStepRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DAG 计划端点测试：创建 → 查询 → 步骤推进 → 完成。
 */
@SpringBootTest(classes = PlanControllerTest.TestConfig.class, properties = {
        "spring.ai.openai.api-key=sk-test-placeholder",
        "spring.ai.openai.chat.options.model=deepseek-chat",
})
class PlanControllerTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EnableJpaRepositories(basePackageClasses = PlanRepository.class)
    @EntityScan(basePackageClasses = PlanEntity.class)
    static class TestConfig {
    }

    @Autowired
    private PlanRepository planRepository;
    @Autowired
    private PlanStepRepository stepRepository;
    @Autowired
    private PlanStepDepRepository depRepository;

    private PlanController controller;

    @BeforeEach
    void setUp() {
        planRepository.deleteAll();
        stepRepository.deleteAll();
        depRepository.deleteAll();
        controller = new PlanController(new PlanService(planRepository, stepRepository, depRepository));
    }

    @Test
    void createGetAdvanceCompleteFlow() {
        Map<String, Object> created = controller.createPlan("s_dag", new PlanController.CreatePlanRequest(
                "部署计划",
                List.of(
                        new PlanController.StepReq("s1", "构建", "mvn package", true),
                        new PlanController.StepReq("s2", "部署", "deploy", true)),
                List.of(new PlanController.DepReq("s2", "s1"))));

        assertEquals("active", ((Map<?, ?>) created.get("plan")).get("status"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> next = (List<Map<String, Object>>) created.get("nextSteps");
        assertEquals(1, next.size());
        assertEquals("s1", next.get(0).get("id"));

        // 查询
        Map<String, Object> current = controller.currentPlan("s_dag");
        assertEquals(Boolean.TRUE, current.get("hasPlan"));

        // 推进 s1 → s2 解锁
        Map<String, Object> after = controller.updateStepStatus("s_dag", "s1",
                new PlanController.UpdateStepRequest(null, "completed"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> next2 = (List<Map<String, Object>>) after.get("nextSteps");
        assertEquals(1, next2.size());
        assertEquals("s2", next2.get(0).get("id"));

        // 完成 s2 → 计划 completed
        controller.updateStepStatus("s_dag", "s2",
                new PlanController.UpdateStepRequest(null, "completed"));
        Map<String, Object> done = controller.currentPlan("s_dag");
        assertEquals("completed", ((Map<?, ?>) done.get("plan")).get("status"));
    }

    @Test
    void noPlanReturnsHasPlanFalse() {
        Map<String, Object> current = controller.currentPlan("s_empty");
        assertEquals(Boolean.FALSE, current.get("hasPlan"));
    }

    @Test
    void cycleRejectedByApi() {
        try {
            controller.createPlan("s_cyc", new PlanController.CreatePlanRequest(
                    "环",
                    List.of(
                            new PlanController.StepReq("a", "A", "", true),
                            new PlanController.StepReq("b", "B", "", true)),
                    List.of(
                            new PlanController.DepReq("a", "b"),
                            new PlanController.DepReq("b", "a"))));
            org.junit.jupiter.api.Assertions.fail("含环计划应被拒绝");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("循环依赖"));
        }
    }

    @Test
    void reviewGateByApi() {
        // spec 工件步骤（kind=spec）：未批准不出现在 nextSteps；批准后出现
        Map<String, Object> created = controller.createPlan("s_rev", new PlanController.CreatePlanRequest(
                "审阅门",
                List.of(
                        new PlanController.StepReq("doc", "导出契约", "契约", true, "spec"),
                        new PlanController.StepReq("impl", "实现", "", true)),
                List.of()));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> next = (List<Map<String, Object>>) created.get("nextSteps");
        assertEquals(List.of("impl"), next.stream().map(m -> m.get("id")).toList(),
                "未批准的 spec 步骤不可执行");

        Map<String, Object> after = controller.reviewStep("s_rev", "doc",
                new PlanController.ReviewStepRequest(null, true));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> next2 = (List<Map<String, Object>>) after.get("nextSteps");
        assertTrue(next2.stream().map(m -> m.get("id")).toList().contains("doc"),
                "批准后 doc 步骤可执行");
        // 步骤视图携带 kind/reviewed
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> steps = (List<Map<String, Object>>) after.get("steps");
        Map<String, Object> doc = steps.stream().filter(m -> "doc".equals(m.get("id")))
                .findFirst().orElseThrow();
        assertEquals("spec", doc.get("kind"));
        assertEquals(Boolean.TRUE, doc.get("reviewed"));
    }
}
