package com.bizfty.anchon.dsh.interaction;

import com.bizfty.anchon.dsh.core.model.SessionId;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 审批策略服务 — per-session 策略 + 权限预设（对应 DSH interaction/permission-presets）。
 * <p>
 * 预设绑定 sandbox 模式与审批策略：
 * workspace-write+ask / danger-full-access+never。
 */
@Service
public class ApprovalPolicyService {

    /** 权限预设。 */
    public enum Preset {
        WORKSPACE_WRITE(ApprovalPolicy.ASK, "workspace-write"),
        DANGER_FULL_ACCESS(ApprovalPolicy.NEVER, "danger-full-access");

        private final ApprovalPolicy policy;
        private final String sandboxMode;

        Preset(ApprovalPolicy policy, String sandboxMode) {
            this.policy = policy;
            this.sandboxMode = sandboxMode;
        }

        public ApprovalPolicy policy() {
            return policy;
        }

        public String sandboxMode() {
            return sandboxMode;
        }
    }

    private final Map<SessionId, ApprovalPolicy> policies = new ConcurrentHashMap<>();

    public ApprovalPolicy policyFor(SessionId sessionId) {
        return policies.getOrDefault(sessionId, ApprovalPolicy.ASK);
    }

    public void setPolicy(SessionId sessionId, ApprovalPolicy policy) {
        policies.put(sessionId, policy);
    }

    /** 应用预设（写策略；sandbox 模式由 sandbox 模块消费，此处仅记录）。 */
    public void applyPreset(SessionId sessionId, Preset preset) {
        policies.put(sessionId, preset.policy());
    }
}
