package com.bizfty.anchon.dsh.sandbox;

import com.bizfty.anchon.dsh.core.model.SessionId;
import com.bizfty.anchon.dsh.tool.SandboxMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 沙箱策略服务测试：默认模式、设置/解析、预设。
 */
class SandboxPolicyServiceTest {

    private final SessionId sessionId = SessionId.of("sess_sb");

    @Test
    void defaultsToWorkspaceWrite() {
        SandboxPolicyService service = new SandboxPolicyService("workspace-write");
        assertEquals(SandboxMode.WORKSPACE_WRITE, service.resolve(sessionId));
    }

    @Test
    void customDefaultFromConfig() {
        SandboxPolicyService service = new SandboxPolicyService("read-only");
        assertEquals(SandboxMode.READ_ONLY, service.resolve(sessionId));
        SandboxPolicyService danger = new SandboxPolicyService("danger-full-access");
        assertEquals(SandboxMode.DANGER_FULL_ACCESS, danger.resolve(sessionId));
    }

    @Test
    void setModeOverridesPerSession() {
        SandboxPolicyService service = new SandboxPolicyService("workspace-write");
        service.setMode(sessionId, SandboxMode.READ_ONLY);
        assertEquals(SandboxMode.READ_ONLY, service.resolve(sessionId));
        // 其他会话不受影响
        assertEquals(SandboxMode.WORKSPACE_WRITE, service.resolve(SessionId.of("other")));
    }

    @Test
    void applyPresetMapsToMode() {
        SandboxPolicyService service = new SandboxPolicyService("workspace-write");
        service.applyPreset(sessionId, "read-only");
        assertEquals(SandboxMode.READ_ONLY, service.resolve(sessionId));
        service.applyPreset(sessionId, "danger-full-access");
        assertEquals(SandboxMode.DANGER_FULL_ACCESS, service.resolve(sessionId));
    }
}
