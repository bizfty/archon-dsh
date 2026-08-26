package com.bizfty.anchon.dsh.todo;

import com.bizfty.anchon.dsh.core.model.SessionId;
import com.bizfty.anchon.dsh.plan.PlanService;
import com.bizfty.anchon.dsh.plan.PlanEntity;
import com.bizfty.anchon.dsh.plan.PlanRepository;
import com.bizfty.anchon.dsh.plan.PlanStepDepRepository;
import com.bizfty.anchon.dsh.plan.PlanStepRepository;
import com.bizfty.anchon.dsh.tool.ToolContext;
import com.bizfty.anchon.dsh.tool.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * todo_write 工具测试 — todo 是 DAG 特殊形式（无依赖计划）：
 * 自动建"待办"计划、按 title upsert、缺失项置 cancelled、状态词汇与 DAG 统一。
 */
@SpringBootTest(classes = TodoWriteToolTest.TestConfig.class, properties = {
        "spring.ai.openai.api-key=sk-test-placeholder",
        "spring.ai.openai.chat.options.model=deepseek-chat",
})
class TodoWriteToolTest {

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

    private PlanService dagPlanService;
    private TodoWriteTool tool;

    @BeforeEach
    void setUp() {
        planRepository.deleteAll();
        stepRepository.deleteAll();
        depRepository.deleteAll();
        dagPlanService = new PlanService(planRepository, stepRepository, depRepository);
        tool = new TodoWriteTool(dagPlanService);
    }

    private ToolContext ctx(String sessionId) {
        return ToolContext.builder().sessionId(SessionId.of(sessionId)).build();
    }

    private Map<String, Object> row(String status, String title, String desc, Boolean required) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", status);
        m.put("title", title);
        m.put("description", desc);
        if (required != null) {
            m.put("required", required);
        }
        return m;
    }

    private com.bizfty.anchon.dsh.tool.ToolCall call(Map<String, Object>... rows) {
        return new com.bizfty.anchon.dsh.tool.ToolCall("call_1", "todo_write",
                new LinkedHashMap<>(Map.of("todos", List.of(rows))));
    }

    @Test
    void firstWriteCreatesTodoPlan() {
        ToolResult result = tool.execute(call(
                row("pending", "任务A", "", null),
                row("in_progress", "任务B", "", null)), ctx("s1"));
        assertTrue(result.success());
        var plan = dagPlanService.currentPlan(SessionId.of("s1")).orElseThrow();
        assertEquals("待办", plan.plan().getTitle());
        assertEquals(2, plan.steps().size());
        assertEquals("任务A", plan.steps().get(0).getTitle());
    }

    @Test
    void rewriteUpsertsByTitleAndCancelsMissing() {
        tool.execute(call(
                row("pending", "A", "", null),
                row("pending", "B", "", null)), ctx("s2"));
        // 第二次：A 完成，B 保留，新增 C → B 缺失应被 cancelled
        ToolResult result = tool.execute(call(
                row("completed", "A", "", null),
                row("pending", "C", "", null)), ctx("s2"));
        assertTrue(result.success());
        var plan = dagPlanService.currentPlan(SessionId.of("s2")).orElseThrow();
        var a = plan.steps().stream().filter(s -> "A".equals(s.getTitle())).findFirst().orElseThrow();
        var b = plan.steps().stream().filter(s -> "B".equals(s.getTitle())).findFirst().orElseThrow();
        var c = plan.steps().stream().filter(s -> "C".equals(s.getTitle())).findFirst().orElseThrow();
        assertEquals("completed", a.getStatus());
        assertEquals("cancelled", b.getStatus());
        assertEquals("pending", c.getStatus());
    }

    @Test
    void requiredFalseAllowsSkip() {
        ToolResult result = tool.execute(call(
                row("skipped", "可选任务", "", false),
                row("pending", "必做任务", "", null)), ctx("s3"));
        assertTrue(result.success());
        var plan = dagPlanService.currentPlan(SessionId.of("s3")).orElseThrow();
        var opt = plan.steps().stream().filter(s -> "可选任务".equals(s.getTitle())).findFirst().orElseThrow();
        assertTrue(!opt.isRequired());
        assertEquals("skipped", opt.getStatus());
    }

    @Test
    void invalidStatusRejected() {
        ToolResult result = tool.execute(call(row("exploded", "X", "", null)), ctx("s4"));
        assertTrue(!result.success());
        assertTrue(result.message().contains("非法状态"));
    }
}
