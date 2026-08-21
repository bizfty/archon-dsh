package com.example.dsh.plan;

import com.example.dsh.core.model.SessionId;
import com.example.dsh.storage.StorageService;
import com.example.dsh.util.JsonUtils;
import org.springframework.stereotype.Component;

/**
 * 计划状态存储 — per-session，经 {@link StorageService} 持久化
 * （对应 DSH plan/plan-mode 的日志化状态；文件后端下重启存活）。
 */
@Component
public class PlanStore {

    private static final String NAMESPACE = "plan";

    /** 计划状态快照。 */
    public record PlanState(boolean active, String planText) {
        public static PlanState inactive() {
            return new PlanState(false, "");
        }
    }

    private final StorageService storage;
    private final JsonUtils jsonUtils;

    public PlanStore(StorageService storage) {
        this.storage = storage;
        this.jsonUtils = new JsonUtils();
    }

    public PlanState get(SessionId sessionId) {
        return storage.get(NAMESPACE, sessionId.value())
                .map(v -> jsonUtils.fromJson(v, PlanState.class))
                .orElse(PlanState.inactive());
    }

    public void set(SessionId sessionId, boolean active, String planText) {
        storage.put(NAMESPACE, sessionId.value(),
                jsonUtils.toJson(new PlanState(active, planText == null ? "" : planText)));
    }

    public boolean isActive(SessionId sessionId) {
        return get(sessionId).active();
    }
}
