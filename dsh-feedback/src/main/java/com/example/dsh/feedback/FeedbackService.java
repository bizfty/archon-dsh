package com.example.dsh.feedback;

import com.example.dsh.core.event.SessionEventBus;
import com.example.dsh.core.event.SessionEventType;
import com.example.dsh.core.model.SessionId;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 反馈服务 — 记录消息反馈（不可变、追加式；不进模型上下文，对应 DSH feedback 契约）。
 */
@Service
public class FeedbackService {

    private final List<FeedbackRecord> records = new CopyOnWriteArrayList<>();
    private final SessionEventBus eventBus;

    public FeedbackService(SessionEventBus eventBus) {
        this.eventBus = eventBus;
    }

    public FeedbackRecord record(SessionId sessionId, String messageId, Integer rating, String comment) {
        if (messageId == null || messageId.isBlank()) {
            throw new IllegalArgumentException("message_id 不能为空");
        }
        if (rating != null && (rating < 1 || rating > 5)) {
            throw new IllegalArgumentException("rating 必须在 1-5 之间");
        }
        FeedbackRecord record = FeedbackRecord.of(sessionId, messageId, rating, comment);
        records.add(record);
        eventBus.publish(sessionId, SessionEventType.FEEDBACK, Map.of(
                "feedbackId", record.id(),
                "messageId", messageId,
                "rating", rating == null ? 0 : rating));
        return record;
    }

    public List<FeedbackRecord> listBySession(SessionId sessionId) {
        return records.stream().filter(r -> r.sessionId().equals(sessionId)).toList();
    }

    public List<FeedbackRecord> listAll() {
        return List.copyOf(records);
    }
}
