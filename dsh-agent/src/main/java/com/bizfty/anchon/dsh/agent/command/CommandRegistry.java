package com.bizfty.anchon.dsh.agent.command;

import com.bizfty.anchon.dsh.core.model.SessionId;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 聊天命令注册表（M6，design-extension-points.md §3.2）：Spring 收集全部 {@link ChatCommand}
 * bean（保序）；`execute(sessionId, message)` 统一识别 `/<name>` 与分发 —— 替代
 * SessionController 的 if 特判。内置 `/help`（列命令）。未知 `/xxx` 与普通消息返回 null
 * （走正常 agent 流程 —— 与原特判行为一致）。
 */
@Component
public class CommandRegistry {

    private final Map<String, ChatCommand> byName = new LinkedHashMap<>();

    public CommandRegistry(List<ChatCommand> discovered) {
        if (discovered != null) {
            for (ChatCommand command : discovered) {
                byName.put(command.name(), command);
            }
        }
    }

    /**
     * 尝试执行聊天命令。
     *
     * @return 命令结果文本；非命令或未知命令 → null（调用方走正常 agent 流程）
     */
    public String execute(SessionId sessionId, String message) {
        if (message == null) {
            return null;
        }
        String trimmed = message.trim();
        if (!trimmed.startsWith("/")) {
            return null; // 普通消息，非命令
        }
        String rest = trimmed.substring(1).trim();
        if (rest.isEmpty()) {
            return null;
        }
        String[] parts = rest.split("\\s+", 2);
        String name = parts[0];
        String args = parts.length > 1 ? parts[1] : "";
        if (name.equals("help")) {
            return helpText();
        }
        ChatCommand command = byName.get(name);
        return command == null ? null : command.execute(sessionId, args);
    }

    /** 注册表内全部命令（不含内置 help；保序）。 */
    public List<ChatCommand> commands() {
        return List.copyOf(byName.values());
    }

    /** /help 文本：内置 help + 全部注册命令。 */
    private String helpText() {
        StringBuilder sb = new StringBuilder("/help - 列出可用命令\n");
        for (ChatCommand command : byName.values()) {
            sb.append('/').append(command.name()).append(" - ").append(command.description()).append('\n');
        }
        return sb.toString().trim();
    }
}
