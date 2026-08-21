package com.example.dsh.plan;

import com.example.dsh.core.model.SessionId;
import org.springframework.stereotype.Service;

/**
 * 计划模式服务 — 软指导（prompt 段 + 退出工具），不自行强制执行。
 * <p>
 * 对应 DSH plan/plan-mode。
 */
@Service
public class PlanModeService {

    private final PlanStore planStore;

    public PlanModeService(PlanStore planStore) {
        this.planStore = planStore;
    }

    public boolean isActive(SessionId sessionId) {
        return planStore.isActive(sessionId);
    }

    public String planText(SessionId sessionId) {
        return planStore.get(sessionId).planText();
    }

    public void enterPlanMode(SessionId sessionId) {
        planStore.set(sessionId, true, planStore.get(sessionId).planText());
    }

    public void exitPlanMode(SessionId sessionId) {
        planStore.set(sessionId, false, planStore.get(sessionId).planText());
    }

    /** 写入计划内容（同时激活）。 */
    public void updatePlan(SessionId sessionId, String planText) {
        planStore.set(sessionId, true, planText);
    }
}
