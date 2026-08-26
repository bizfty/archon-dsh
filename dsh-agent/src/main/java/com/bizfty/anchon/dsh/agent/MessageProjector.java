package com.bizfty.anchon.dsh.agent;

import com.bizfty.anchon.dsh.compaction.ToolResultPruner;
import com.bizfty.anchon.dsh.core.model.MessageRole;
import com.bizfty.anchon.dsh.core.model.SessionMessage;
import com.bizfty.anchon.dsh.util.JsonUtils;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 消息投影 — 会话日志消息 → Spring AI 消息（对应 DSH session 的 deriveMessages）。
 * <p>
 * TOOL 行需要 toolCallId/toolName 才能重建 ToolResponseMessage；
 * ASSISTANT 行的工具调用从持久化的 toolCallsJson 重建。
 * <p>
 * 注入 {@link ToolResultPruner}（可空）时，超大工具结果在投影层截断
 * （回放安全：日志保留原文，仅模型可见面收窄）。
 */
@Component
public class MessageProjector {

    private final JsonUtils jsonUtils;
    private final ToolResultPruner pruner;

    public MessageProjector(JsonUtils jsonUtils) {
        this(jsonUtils, null);
    }

    @Autowired
    public MessageProjector(JsonUtils jsonUtils, ToolResultPruner pruner) {
        this.jsonUtils = jsonUtils;
        this.pruner = pruner;
    }

    public Message project(SessionMessage message) {
        return switch (message.role()) {
            case USER -> new UserMessage(message.content());
            case SYSTEM -> new SystemMessage(message.content());
            case ASSISTANT -> projectAssistant(message);
            case TOOL -> ToolResponseMessage.builder()
                    .responses(List.of(new ToolResponseMessage.ToolResponse(
                            message.toolCallId(), message.toolName(), prunedContent(message.content()))))
                    .build();
        };
    }

    /** 超大工具结果 → 头 + 标记 + 尾（无 pruner 时原样）。 */
    private String prunedContent(String content) {
        if (pruner == null) {
            return content;
        }
        return pruner.prune(content);
    }

    private Message projectAssistant(SessionMessage message) {
        if (!message.hasToolCalls()) {
            return new AssistantMessage(message.content());
        }
        return AssistantMessage.builder()
                .content(message.content())
                .toolCalls(parseToolCalls(message.toolCallsJson()))
                .build();
    }

    private List<AssistantMessage.ToolCall> parseToolCalls(String json) {
        List<Map<String, Object>> list = jsonUtils.toList(json);
        return list.stream()
                .map(m -> new AssistantMessage.ToolCall(
                        String.valueOf(m.get("id")),
                        m.get("type") == null ? "function" : String.valueOf(m.get("type")),
                        String.valueOf(m.get("name")),
                        String.valueOf(m.get("arguments"))))
                .toList();
    }
}
