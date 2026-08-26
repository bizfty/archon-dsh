package com.bizfty.anchon.dsh.api;

import com.bizfty.anchon.dsh.core.model.SessionId;
import com.bizfty.anchon.dsh.feedback.FeedbackRecord;
import com.bizfty.anchon.dsh.feedback.FeedbackService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 消息反馈端点（对应 DSH message-feedback：评分/备注 sidecar，不进模型上下文）。
 */
@RestController
@RequestMapping("/api/feedback")
public class FeedbackController {

    private final FeedbackService feedbackService;

    public FeedbackController(FeedbackService feedbackService) {
        this.feedbackService = feedbackService;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> record(@RequestBody RecordRequest request) {
        try {
            FeedbackRecord record = feedbackService.record(
                    SessionId.of(request.sessionId()),
                    request.messageId(),
                    request.rating(),
                    request.comment());
            return ResponseEntity.ok(Map.of("feedback_id", record.id()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping
    public List<FeedbackRecord> list(@RequestParam(required = false) String session_id) {
        if (session_id == null || session_id.isBlank()) {
            return feedbackService.listAll();
        }
        return feedbackService.listBySession(SessionId.of(session_id));
    }

    public record RecordRequest(String sessionId, String messageId, Integer rating, String comment) {
    }
}
