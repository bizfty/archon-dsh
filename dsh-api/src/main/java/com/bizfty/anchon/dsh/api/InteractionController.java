package com.bizfty.anchon.dsh.api;

import com.bizfty.anchon.dsh.interaction.ApprovalRequest;
import com.bizfty.anchon.dsh.interaction.InMemoryApprovalProvider;
import com.bizfty.anchon.dsh.interaction.InMemoryUserQuestionProvider;
import com.bizfty.anchon.dsh.interaction.UserQuestion;
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

/**
 * 人机协作端点 — 待审批列表 / 审批应答 / 待回答问题 / 问题应答。
 * <p>
 * 对应 DSH interaction 的 Web 应答渠道；模型在工具调用中阻塞等待时，
 * 用户经这些端点完成应答，挂起的虚拟线程随即恢复。
 */
@RestController
@RequestMapping("/api/interactions")
public class InteractionController {

    private final InMemoryApprovalProvider approvalProvider;
    private final InMemoryUserQuestionProvider questionProvider;

    public InteractionController(InMemoryApprovalProvider approvalProvider,
                                 InMemoryUserQuestionProvider questionProvider) {
        this.approvalProvider = approvalProvider;
        this.questionProvider = questionProvider;
    }

    @GetMapping("/approvals/pending")
    public List<Map<String, Object>> pendingApprovals() {
        return approvalProvider.pendingRequests().stream()
                .map(a -> Map.<String, Object>of(
                        "id", a.id(),
                        "tool", a.toolName(),
                        "reason", a.reason(),
                        "created_at", a.createdAt().toString()))
                .toList();
    }

    @PostMapping("/approvals/{id}/approve")
    public ResponseEntity<Map<String, Object>> approve(@PathVariable String id) {
        approvalProvider.approve(id);
        return ResponseEntity.ok(Map.of("id", id, "decision", "allowed"));
    }

    @PostMapping("/approvals/{id}/reject")
    public ResponseEntity<Map<String, Object>> reject(@PathVariable String id,
                                                      @RequestBody(required = false) Map<String, String> body) {
        approvalProvider.reject(id, body == null ? null : body.get("reason"));
        return ResponseEntity.ok(Map.of("id", id, "decision", "rejected"));
    }

    @GetMapping("/questions/pending")
    public List<Map<String, Object>> pendingQuestions(
            @RequestParam(required = false) String sessionId) {
        return questionProvider.pendingQuestions().stream()
                .filter(q -> sessionId == null || sessionId.isBlank() || sessionId.equals(q.sessionId()))
                .map(q -> Map.<String, Object>of(
                        "id", q.id(),
                        "session_id", q.sessionId() == null ? "" : q.sessionId(),
                        "question", q.question(),
                        "options", q.options(),
                        "multi_select", q.multiSelect()))
                .toList();
    }

    @PostMapping("/questions/{id}/answer")
    public ResponseEntity<Map<String, Object>> answer(@PathVariable String id,
                                                      @RequestBody Map<String, String> body) {
        String answer = body == null ? null : body.get("answer");
        if (answer == null || answer.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "answer 不能为空"));
        }
        questionProvider.answer(id, answer);
        return ResponseEntity.ok(Map.of("id", id, "answer", answer));
    }
}
