package com.bizfty.anchon.dsh.agent.command;

import com.bizfty.anchon.dsh.agent.AgentLoopService;
import com.bizfty.anchon.dsh.core.model.SessionId;
import org.springframework.stereotype.Component;

/**
 * `/compact` 命令（M6 收编自 SessionController.tryCommand 特判）：手动压缩 — 不经过模型 turn，
 * 经由 {@link AgentLoopService#manualCompact} 门面委托（M4：入 ResidentAgent 命令队列，running
 * 时排队等当前 turn 完成）。
 */
@Component
public class CompactChatCommand implements ChatCommand {

    private final AgentLoopService agentLoopService;

    public CompactChatCommand(AgentLoopService agentLoopService) {
        this.agentLoopService = agentLoopService;
    }

    @Override
    public String name() {
        return "compact";
    }

    @Override
    public String description() {
        return "手动压缩会话历史（不经过模型 turn）";
    }

    @Override
    public String execute(SessionId sessionId, String args) {
        if (args != null && !args.isBlank()) {
            return "Usage: /compact (no arguments)";
        }
        return agentLoopService.manualCompact(sessionId);
    }
}
