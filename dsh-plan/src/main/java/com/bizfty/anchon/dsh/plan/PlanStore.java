package com.bizfty.anchon.dsh.plan;

import com.bizfty.anchon.dsh.core.model.SessionId;
import com.bizfty.anchon.dsh.core.model.SessionSettings;
import com.bizfty.anchon.dsh.storage.StorageService;
import com.bizfty.anchon.dsh.util.JsonUtils;
import org.springframework.stereotype.Component;

/**
 * 计划模式状态存储 — 会话级设置（namespace=session.&lt;id&gt;, key=plan-mode）。
 * <p>
 * 对应 DSH plan/plan-mode 的日志化状态；文件后端下重启存活。
 */
@Component
public class PlanStore {

    private final StorageService storage;
    private final JsonUtils jsonUtils;

    /** 计划状态快照。 */
    public record PlanState(boolean active, String planText) {
        public static PlanState inactive() {
            return new PlanState(false, "");
        }
    }

    public PlanStore(StorageService storage) {
        this.storage = storage;
        this.jsonUtils = new JsonUtils();
    }

    public PlanState get(SessionId sessionId) {
        return storage.get(SessionSettings.namespace(sessionId), SessionSettings.KEY_PLAN_MODE)
                .map(v -> jsonUtils.fromJson(v, PlanState.class))
                .orElse(PlanState.inactive());
    }

    public void set(SessionId sessionId, boolean active, String planText) {
        storage.put(SessionSettings.namespace(sessionId), SessionSettings.KEY_PLAN_MODE,
                jsonUtils.toJson(new PlanState(active, planText == null ? "" : planText)));
    }

    public boolean isActive(SessionId sessionId) {
        return get(sessionId).active();
    }
}
