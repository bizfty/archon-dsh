package com.bizfty.anchon.dsh.api;

import com.bizfty.anchon.dsh.goal.Goal;
import com.bizfty.anchon.dsh.goal.GoalService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Optional;

/**
 * 目标端点：查询/创建/更新会话目标（与 create_goal/update_goal/get_goal 工具同源）。
 */
@RestController
@RequestMapping("/api/goals")
public class GoalController {

    private final GoalService goals;

    public GoalController(GoalService goals) {
        this.goals = goals;
    }

    @GetMapping
    public ResponseEntity<?> current(@RequestParam String sessionId) {
        Optional<ResponseEntity<?>> found = goals.current(sessionId)
                .<ResponseEntity<?>>map(g -> ResponseEntity.ok(g.view()));
        // Map.of 不允许 null value：用 LinkedHashMap 表达 {"goal": null}
        java.util.Map<String, Object> empty = new java.util.LinkedHashMap<>();
        empty.put("goal", null);
        return found.orElseGet(() -> ResponseEntity.ok(empty));
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody CreateRequest request) {
        try {
            Goal goal = goals.create(request.sessionId(), request.objective(), request.maxGoalRounds());
            return ResponseEntity.ok(goal.view());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping
    public ResponseEntity<?> update(@RequestBody UpdateRequest request) {
        try {
            Goal goal = goals.update(request.sessionId(), request.goalId(), request.revision(),
                    request.action(), request.objective(), request.maxGoalRounds(),
                    request.blockedCode(), request.blockedReason());
            return ResponseEntity.ok(goal.view());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    public record CreateRequest(String sessionId, String objective, Integer maxGoalRounds) {
    }

    public record UpdateRequest(String sessionId, String goalId, int revision, String action,
                                String objective, Integer maxGoalRounds,
                                String blockedCode, String blockedReason) {
    }
}
