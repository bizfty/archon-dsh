package com.bizfty.anchon.dsh.compaction;

import com.bizfty.anchon.dsh.core.event.SessionEventBus;
import com.bizfty.anchon.dsh.core.event.SessionEventType;
import com.bizfty.anchon.dsh.core.model.MessageRole;
import com.bizfty.anchon.dsh.core.model.SessionId;
import com.bizfty.anchon.dsh.core.model.SessionMessage;
import com.bizfty.anchon.dsh.llm.LlmGateway;
import com.bizfty.anchon.dsh.llm.ModelCallEventPayloads;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 历史压缩服务（对应 DSH compaction/compaction-basic）。
 * <p>
 * 语义：**表面替换而非删日志** — 被压缩的历史保留在会话日志中（确定性回放），
 * 压缩产物是一个新的摘要用户消息 + 保留尾部；由 agent-loop 持久化摘要后
 * 以「摘要 + 尾部」作为派生历史。
 * <p>
 * 摘要的 LLM 调用（经由网关）会发布 {@code MODEL_REQUEST/MODEL_RESPONSE} 事件
 * （{@code callSite=compaction}），与 agent 对话共用载荷 schema；仅在持有
 * sessionId 且装配了事件总线时发布（直接 new 的测试场景不发）。
 */
@Service
public class CompactionService {

    private static final Logger log = LoggerFactory.getLogger(CompactionService.class);
    private static final int AVG_CHARS_PER_TOKEN = 4;

    private final CompactionProperties properties;
    /** 事件总线（Spring 装配时非空；直接 new 的测试场景可传 null → 不发事件）。 */
    private final SessionEventBus eventBus;

    public CompactionService(CompactionProperties properties) {
        this(properties, null);
    }

    @Autowired
    public CompactionService(CompactionProperties properties, SessionEventBus eventBus) {
        this.properties = properties;
        this.eventBus = eventBus;
    }

    /** token 估算：字符数 / 4 + 每消息开销。 */
    public long estimateTokens(SessionMessage message) {
        long chars = message.content() == null ? 0 : message.content().length();
        if (message.toolCallsJson() != null) {
            chars += message.toolCallsJson().length();
        }
        return chars / AVG_CHARS_PER_TOKEN + 4;
    }

    public long estimateTokens(List<SessionMessage> history) {
        return history.stream().mapToLong(this::estimateTokens).sum();
    }

    public boolean needsCompaction(List<SessionMessage> history) {
        if (!properties.enabled() || history.size() <= properties.keepTailMessages()) {
            return false;
        }
        return estimateTokens(history) > properties.tokenThreshold();
    }

    /** 压缩计划：摘要 + 保留尾部的起点（不发布事件，供无需会话上下文/测试场景使用）。 */
    public CompressionPlan compress(List<SessionMessage> history, LlmGateway gateway) {
        return compress(null, history, gateway);
    }

    /**
     * 压缩计划：摘要 + 保留尾部的起点。
     *
     * @param sessionId 所属会话（非 null 且装配了事件总线时，摘要 LLM 调用会发布 MODEL_* 事件）
     * @param history   完整历史
     * @param gateway   摘要用模型网关（失败时退化为确定性摘要）
     */
    public CompressionPlan compress(SessionId sessionId, List<SessionMessage> history, LlmGateway gateway) {
        int keep = Math.min(properties.keepTailMessages(), Math.max(0, history.size() - 1));
        int headEnd = history.size() - keep;
        List<SessionMessage> head = new ArrayList<>(history.subList(0, headEnd));
        List<SessionMessage> tail = new ArrayList<>(history.subList(headEnd, history.size()));

        String summary = summarize(sessionId, head, gateway);
        return new CompressionPlan(summary, tail, head.size());
    }

    /** 压缩计划。 */
    public record CompressionPlan(String summaryText, List<SessionMessage> tail, int compressedCount) {
    }

    private String summarize(SessionId sessionId, List<SessionMessage> head, LlmGateway gateway) {
        if (head.isEmpty()) {
            return "（无历史可压缩）";
        }
        try {
            StringBuilder sb = new StringBuilder();
            for (SessionMessage m : head) {
                sb.append(m.role()).append(": ");
                if (m.role() == MessageRole.TOOL) {
                    sb.append("[工具结果 ").append(m.toolName()).append("]");
                } else {
                    String content = m.content() == null ? "" : m.content();
                    sb.append(content.length() > 500 ? content.substring(0, 500) + "…" : content);
                }
                sb.append('\n');
            }
            List<Message> messages = List.of(
                    new SystemMessage("你是会话历史压缩器。把下面的对话历史压缩为一份简洁的摘要，"
                            + "保留：用户目标、已完成的动作与结果、未完成事项、关键事实。"
                            + "用与历史相同的语言，不超过 " + properties.maxSummaryCharacters() + " 字符。"),
                    new UserMessage(sb.toString()));
            OpenAiChatOptions options = OpenAiChatOptions.builder()
                    .model(gateway.defaultModel())
                    .temperature(0.2)
                    .build();
            String model = options.getModel() == null ? gateway.defaultModel() : options.getModel();
            boolean trace = eventBus != null && sessionId != null;
            if (trace) {
                eventBus.publish(sessionId, SessionEventType.MODEL_REQUEST,
                        ModelCallEventPayloads.requestPayload(model, messages, options,
                                ModelCallEventPayloads.CALL_SITE_COMPACTION));
            }
            ChatResponse response = gateway.call(messages, options);
            Generation generation = response.getResult();
            String rawText = generation == null || generation.getOutput() == null
                    ? null : generation.getOutput().getText();
            if (trace && rawText != null) {
                eventBus.publish(sessionId, SessionEventType.MODEL_RESPONSE,
                        ModelCallEventPayloads.responsePayload(model,
                                generation.getMetadata() == null ? null : generation.getMetadata().getFinishReason(),
                                rawText, null,
                                response.getMetadata() == null ? null : response.getMetadata().getUsage(),
                                ModelCallEventPayloads.CALL_SITE_COMPACTION));
            }
            if (rawText != null && !rawText.isBlank()) {
                return trimToLimit(rawText);
            }
        } catch (Exception e) {
            log.warn("[Compaction] 摘要调用失败，退化为确定性摘要: {}", e.getMessage());
        }
        // 确定性回退：不依赖模型的摘要
        SessionMessage first = head.get(0);
        SessionMessage last = head.get(head.size() - 1);
        return "（历史已省略 " + head.size() + " 条消息，从 " + first.createdAt() + " 至 "
                + last.createdAt() + " — 摘要生成失败，已压缩以控制上下文长度）";
    }

    private String trimToLimit(String text) {
        if (text.length() <= properties.maxSummaryCharacters()) {
            return text;
        }
        return text.substring(0, properties.maxSummaryCharacters()) + "…";
    }
}
