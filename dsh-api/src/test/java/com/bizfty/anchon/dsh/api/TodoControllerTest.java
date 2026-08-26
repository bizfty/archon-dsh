package com.bizfty.anchon.dsh.api;

import com.bizfty.anchon.dsh.core.model.SessionId;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 待办端点测试（todo = DAG 扁平化视图）：
 * 无 plan → hasTodos=false；建无依赖 plan → 计数与逐项（含 required）。
 */
@SpringBootTest(classes = TodoControllerTest.TestConfig.class, properties = {
        "spring.ai.openai.api-key=sk-test-placeholder",
        "spring.ai.openai.chat.options.model=deepseek-chat",
})
class TodoControllerTest {

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

    private TodoController controller;

    @BeforeEach
    void setUp() {
        planRepository.deleteAll();
        stepRepository.deleteAll();
        depRepository.deleteAll();
        controller = new TodoController(new PlanService(planRepository, stepRepository, depRepository));
    }

    @Test
    void noPlanReturnsHasTodosFalse() {
        Map<String, Object> view = controller.todos("s_t1");
        assertEquals(Boolean.FALSE, view.get("hasTodos"));
    }

    @Test
    void flatPlanStepsReturnCountsAndItems() {
        // 建无依赖 plan（等价 todo 清单）
        PlanService dag = new PlanService(planRepository, stepRepository, depRepository);
        dag.createPlan(SessionId.of("s_t2"), "待办",
                List.of(
                        new PlanService.StepSpec("s1", "梳理需求", "", true),
                        new PlanService.StepSpec("s2", "实现 fixture", "", false),
                        new PlanService.StepSpec("s3", "跑后台构建", "", true),
                        new PlanService.StepSpec("s4", "浏览器验收", "", true)),
                List.of());
        Map<String, Object> view = controller.todos("s_t2");
        assertEquals(Boolean.TRUE, view.get("hasTodos"));
        assertEquals(4, view.get("total"));
        assertEquals(0L, view.get("completed"));
        assertEquals(0L, view.get("inProgress"));
        assertEquals(4L, view.get("pending"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) view.get("items");
        assertEquals(4, items.size());
        assertEquals("s1", items.get(0).get("planStepId"));
        assertEquals(Boolean.TRUE, items.get(0).get("required"));
        assertEquals(Boolean.FALSE, items.get(1).get("required"));
    }
}
