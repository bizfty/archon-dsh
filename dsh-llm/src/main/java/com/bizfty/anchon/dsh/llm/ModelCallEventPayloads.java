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
 * <p>
 * 系列标注（C-①，对应上游 durable request/header 的 initial/resume/change/series 词汇）：
 * 可选 {@link RequestSeriesInfo} 描述该请求属于哪个请求系列。Java 保留「全量 messages
 * 内联 + 每 step 一条 MODEL_REQUEST」语义（不引入 durable header 信封），因此用
 * {@code stepInSeries} + {@code startsSeries} 让下游能区分「系列首请求」与
 * 「同系列内后续 step 的重复副本」，便于折叠/审计。不带 series 的调用（辅助调用点
 * session_title / compaction、旧数据）载荷中无 {@code requestSeries} 键。
 */
public final class ModelCallEventPayloads {

    /** 调用点：agent 对话 step 循环。 */
    public static final String CALL_SITE_AGENT_TURN = "agent_turn";
    /** 调用点：会话标题生成（首轮消息后的辅助调用）。 */
    public static final String CALL_SITE_SESSION_TITLE = "session_title";
    /** 调用点：历史压缩摘要。 */
    public static final String CALL_SITE_COMPACTION = "compaction";

    /** 系列 reason 词汇（对齐上游 request/header 的 EpochHeader reason）。 */
    public static final String SERIES_REASON_INITIAL = "initial";
    public static final String SERIES_REASON_RESUME = "resume";
    public static final String SERIES_REASON_CHANGE = "change";
    public static final String SERIES_REASON_SERIES = "series";

    /**
     * 请求系列标注 — 该 MODEL_REQUEST 所属系列的边界与步内位置。
     *
     * @param seriesId    系列标识（同系列内全部请求共享）
     * @param reason      系列开启原因：initial / resume / change / series
     *                    （语义见 {@link #SERIES_REASON_INITIAL} 等；续步沿用边界 reason）
     * @param startsSeries 是否为本系列的首个请求（同系列后续 step 为 false）
     * @param stepInSeries 系列内 step 序号（1 基；重试不递增，与系列内重复副本一一对应）
     */
    public record RequestSeriesInfo(String seriesId, String reason,
                                    boolean startsSeries, int stepInSeries) {

        /** 序列化为 payload 内嵌 Map（供事件 JSON 持久化）。 */
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("seriesId", seriesId);
            m.put("reason", reason);
            m.put("startsSeries", startsSeries);
            m.put("stepInSeries", stepInSeries);
            return m;
        }
    }

    private ModelCallEventPayloads() {
    }

    /**
     * MODEL_REQUEST 载荷：model + callSite + 完整请求消息 + 非敏感选项。
     * 不带系列标注（旧调用点/辅助调用默认走此重载）。
     */
    public static Map<String, Object> requestPayload(String model, List<Message> messages,
                                                     OpenAiChatOptions options, String callSite) {
        return requestPayload(model, messages, options, callSite, null);
    }

    /**
     * MODEL_REQUEST 载荷（带可选系列标注）：model + callSite + 完整请求消息 +
     * 非敏感选项 + {@code requestSeries}（series 为 null 时不写该键）。
     */
    public static Map<String, Object> requestPayload(String model, List<Message> messages,
                                                     OpenAiChatOptions options, String callSite,
                                                     RequestSeriesInfo series) {
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
        if (series != null) {
            payload.put("requestSeries", series.toMap());
        }
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
