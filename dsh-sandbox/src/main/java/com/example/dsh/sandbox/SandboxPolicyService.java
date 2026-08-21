package com.example.dsh.sandbox;

import com.example.dsh.core.model.SessionId;
import com.example.dsh.tool.SandboxMode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 沙箱策略服务 — 每会话沙箱模式（对应 DSH sandbox/sandbox-policy）。
 * <p>
 * 解析优先级：会话显式设置 > 部署默认（WORKSPACE_WRITE）。
 * 模式是策略词汇，由 fs/shell 等工具经 ToolContext 消费（策略栅栏，非内核边界）。
 */
@Service
public class SandboxPolicyService {

    private final Map<SessionId, SandboxMode> modes = new ConcurrentHashMap<>();
    private final SandboxMode defaultMode;

    public SandboxPolicyService(@Value("${dsh.sandbox.default-mode:workspace-write}") String defaultMode) {
        this.defaultMode = parse(defaultMode);
    }

    public SandboxMode resolve(SessionId sessionId) {
        return modes.getOrDefault(sessionId, defaultMode);
    }

    public void setMode(SessionId sessionId, SandboxMode mode) {
        modes.put(sessionId, mode);
    }

    /** 预设：workspace-write / danger-full-access（对应权限预设的 sandbox 旋钮）。 */
    public void applyPreset(SessionId sessionId, String preset) {
        setMode(sessionId, parse(preset));
    }

    private SandboxMode parse(String text) {
        return switch (text == null ? "" : text.trim().toLowerCase().replace('-', '_')) {
            case "read_only", "read-only" -> SandboxMode.READ_ONLY;
            case "danger_full_access", "danger-full-access", "danger" -> SandboxMode.DANGER_FULL_ACCESS;
            default -> SandboxMode.WORKSPACE_WRITE;
        };
    }
}
