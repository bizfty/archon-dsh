package com.bizfty.anchon.dsh.agent;

import com.bizfty.anchon.dsh.core.event.SessionEventBus;
import com.bizfty.anchon.dsh.core.event.SessionEventType;
import com.bizfty.anchon.dsh.core.model.SessionId;
import com.bizfty.anchon.dsh.llm.LlmGateway;
import com.bizfty.anchon.dsh.llm.ModelCallEventPayloads;
import com.bizfty.anchon.dsh.session.SessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 会话标题生成（对应 DSH session-title-llm）：首轮用户消息后以辅助调用生成标题并持久化。
 * <p>
 * 失败不阻断对话（仅记录）；可配置禁用。该辅助 LLM 调用会发布
 * {@code MODEL_REQUEST/MODEL_RESPONSE} 事件（{@code callSite=session_title}），
 * 与 agent 对话 step 共用同一载荷 schema，便于统一追踪全部 LLM 调用。
 */
@Service
public class SessionTitleService {

    private static final Logger log = LoggerFactory.getLogger(SessionTitleService.class);

    private final LlmGateway llmGateway;
    private final SessionService sessionService;
    private final boolean enabled;
    /** 事件总线（Spring 装配时非空；直接 new 的测试场景可传 null → 不发事件）。 */
    private final SessionEventBus eventBus;

    public SessionTitleService(LlmGateway llmGateway,
                               SessionService sessionService,
                               @Value("${dsh.session.title-llm.enabled:true}") boolean enabled) {
        this(llmGateway, sessionService, enabled, null);
    }

    @Autowired
    public SessionTitleService(LlmGateway llmGateway,
                               SessionService sessionService,
                               @Value("${dsh.session.title-llm.enabled:true}") boolean enabled,
                               SessionEventBus eventBus) {
        this.llmGateway = llmGateway;
        this.sessionService = sessionService;
        this.enabled = enabled;
        this.eventBus = eventBus;
    }

    /**
     * 为会话生成标题（仅当当前无标题且启用时）。
     *
     * @return 是否生成了标题
     */
    public boolean maybeTitle(SessionId sessionId, String firstUserMessage) {
        return maybeTitle(sessionId, firstUserMessage, null);
    }

    /**
     * 为会话生成标题（仅当当前无标题且启用时）。
     *
     * @param executionId 所属 turn 的执行标识（非 null 时辅助 MODEL_REQUEST 带独立系列
     *                    {@code title-<executionId>}，P2-①；null = 旧调用/测试，不带系列）
     */
    public boolean maybeTitle(SessionId sessionId, String firstUserMessage, String executionId) {
        if (!enabled || firstUserMessage == null || firstUserMessage.isBlank()) {
            return false;
        }
        try {
            var session = sessionService.getSession(sessionId);
            if (session.title() != null && !session.title().isBlank()) {
                return false; // 已有标题
            }
            List<Message> messages = List.of(
                    new SystemMessage("为对话生成一个简短标题（≤20 字，不含引号，直接输出标题文本）"),
                    new UserMessage(firstUserMessage.length() > 500
                            ? firstUserMessage.substring(0, 500) : firstUserMessage));
            OpenAiChatOptions options = OpenAiChatOptions.builder()
                    .model(llmGateway.defaultModel())
                    .temperature(0.3)
                    .build();
            String model = options.getModel() == null ? llmGateway.defaultModel() : options.getModel();
            if (eventBus != null) {
                ModelCallEventPayloads.RequestSeriesInfo series = executionId == null
                        ? null
                        : new ModelCallEventPayloads.RequestSeriesInfo("title-" + executionId,
                                ModelCallEventPayloads.SERIES_REASON_INITIAL, true, 1);
                eventBus.publish(sessionId, SessionEventType.MODEL_REQUEST,
                        ModelCallEventPayloads.requestPayload(model, messages, options,
                                ModelCallEventPayloads.CALL_SITE_SESSION_TITLE, series));
            }
            ChatResponse response = llmGateway.call(messages, options);
            Generation generation = response.getResult();
            String rawTitle = generation == null || generation.getOutput() == null
                    ? null : generation.getOutput().getText();
            if (eventBus != null && rawTitle != null) {
                eventBus.publish(sessionId, SessionEventType.MODEL_RESPONSE,
                        ModelCallEventPayloads.responsePayload(model,
                                generation.getMetadata() == null ? null : generation.getMetadata().getFinishReason(),
                                rawTitle, null,
                                response.getMetadata() == null ? null : response.getMetadata().getUsage(),
                                ModelCallEventPayloads.CALL_SITE_SESSION_TITLE));
            }
            if (rawTitle != null && !rawTitle.isBlank()) {
                String cleaned = rawTitle.replace("\n", " ").trim();
                if (cleaned.length() > 40) {
                    cleaned = cleaned.substring(0, 40) + "…";
                }
                sessionService.updateTitle(sessionId, cleaned);
                return true;
            }
        } catch (Exception e) {
            log.warn("[SessionTitle] 标题生成失败（忽略）: {}", e.getMessage());
        }
        return false;
    }
}
