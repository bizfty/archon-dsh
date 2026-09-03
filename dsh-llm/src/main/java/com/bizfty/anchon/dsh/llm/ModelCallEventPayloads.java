package com.bizfty.anchon.dsh.llm;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MODEL_REQUEST / MODEL_RESPONSE 事件载荷工厂 — 模型调用面追踪的统一 schema。
 * <p>
 * agent 对话 step（{@code agent_turn}）、会话标题生成（{@code session_title}）、
 * 历史压缩摘要（{@code compaction}）等所有 LLM 调用点共用此载荷格式，通过
 * {@code callSite} 区分来源，便于下游统一查询与按视角过滤。
 * <p>
 * 请求载荷保留调用时的完整上下文快照（model + messages + 非敏感 options），
 * 每条事件自包含、可独立审计「模型当时看到了什么」；响应载荷携带
 * finishReason / text / toolCalls / usage。载荷不含 apiKey、工具回调实现等
 * 敏感或运行时对象。
 */
public final class ModelCallEventPayloads {

    /** 调用点：agent 对话 step 循环。 */
    public static final String CALL_SITE_AGENT_TURN = "agent_turn";
    /** 调用点：会话标题生成（首轮消息后的辅助调用）。 */
    public static final String CALL_SITE_SESSION_TITLE = "session_title";
    /** 调用点：历史压缩摘要。 */
    public static final String CALL_SITE_COMPACTION = "compaction";

    private ModelCallEventPayloads() {
    }

    /**
     * MODEL_REQUEST 载荷：model + callSite + 完整请求消息 + 非敏感选项。
     *
     * @param model    模型名
     * @param messages 调用时发送的完整消息列表（快照）
     * @param options  调用选项（仅白名单字段，不含 apiKey / 工具回调实现）
     * @param callSite 调用点来源，见 {@code CALL_SITE_*} 常量
     */
    public static Map<String, Object> requestPayload(String model, List<Message> messages,
                                                     OpenAiChatOptions options, String callSite) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model);
        payload.put("callSite", callSite);
        payload.put("messages", serializeMessages(messages));
        Map<String, Object> opts = new LinkedHashMap<>();
        if (options != null) {
            if (options.getTemperature() != null) opts.put("temperature", options.getTemperature());
            if (options.getTopP() != null) opts.put("topP", options.getTopP());
            if (options.getTopK() != null) opts.put("topK", options.getTopK());
            if (options.getMaxTokens() != null) opts.put("maxTokens", options.getMaxTokens());
            if (options.getFrequencyPenalty() != null) opts.put("frequencyPenalty", options.getFrequencyPenalty());
            if (options.getPresencePenalty() != null) opts.put("presencePenalty", options.getPresencePenalty());
        }
        payload.put("options", opts);
        return payload;
    }

    /**
     * MODEL_RESPONSE 载荷：model + callSite + finish reason + 输出文本 + 工具调用 + usage。
     */
    public static Map<String, Object> responsePayload(String model, String finishReason, String text,
                                                      List<AssistantMessage.ToolCall> toolCalls, Usage usage,
                                                      String callSite) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model);
        payload.put("callSite", callSite);
        if (finishReason != null) {
            payload.put("finishReason", finishReason);
        }
        if (text != null) {
            payload.put("text", text);
        }
        if (toolCalls != null && !toolCalls.isEmpty()) {
            payload.put("toolCalls", serializeToolCalls(toolCalls));
        }
        if (usage != null) {
            Map<String, Object> u = new LinkedHashMap<>();
            if (usage.getPromptTokens() != null) u.put("promptTokens", usage.getPromptTokens());
            if (usage.getCompletionTokens() != null) u.put("completionTokens", usage.getCompletionTokens());
            if (usage.getTotalTokens() != null) u.put("totalTokens", usage.getTotalTokens());
            payload.put("usage", u);
        }
        return payload;
    }

    /** 工具调用 → JSON 友好 Map（与持久化 tool_calls 同构，可供 parseToolCalls 反解）。 */
    public static List<Map<String, String>> serializeToolCalls(List<AssistantMessage.ToolCall> toolCalls) {
        return toolCalls.stream()
                .map(tc -> {
                    Map<String, String> m = new LinkedHashMap<>();
                    m.put("id", tc.id());
                    m.put("type", tc.type());
                    m.put("name", tc.name());
                    m.put("arguments", tc.arguments());
                    return m;
                })
                .toList();
    }

    /** 请求消息列表 → JSON 友好 Map 列表（含 role/content/toolCalls/toolResponses）。 */
    private static List<Map<String, Object>> serializeMessages(List<Message> messages) {
        return messages.stream().map(ModelCallEventPayloads::serializeMessage).toList();
    }

    private static Map<String, Object> serializeMessage(Message message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", message.getMessageType() == null ? null : message.getMessageType().name().toLowerCase());
        if (message instanceof AssistantMessage am) {
            m.put("content", am.getText());
            if (am.hasToolCalls()) {
                m.put("toolCalls", serializeToolCalls(am.getToolCalls()));
            }
        } else if (message instanceof ToolResponseMessage trm) {
            m.put("toolResponses", trm.getResponses().stream().map(r -> {
                Map<String, Object> rm = new LinkedHashMap<>();
                rm.put("id", r.id());
                rm.put("name", r.name());
                rm.put("content", r.responseData());
                return rm;
            }).toList());
        } else {
            m.put("content", message.getText());
        }
        return m;
    }
}
