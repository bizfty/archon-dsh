package com.example.dsh.agent;

import com.example.dsh.core.model.SessionId;
import com.example.dsh.llm.LlmGateway;
import com.example.dsh.session.SessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 会话标题生成（对应 DSH session-title-llm）：首轮用户消息后以辅助调用生成标题并持久化。
 * <p>
 * 失败不阻断对话（仅记录）；可配置禁用。
 */
@Service
public class SessionTitleService {

    private static final Logger log = LoggerFactory.getLogger(SessionTitleService.class);

    private final LlmGateway llmGateway;
    private final SessionService sessionService;
    private final boolean enabled;

    public SessionTitleService(LlmGateway llmGateway,
                               SessionService sessionService,
                               @Value("${dsh.session.title-llm.enabled:true}") boolean enabled) {
        this.llmGateway = llmGateway;
        this.sessionService = sessionService;
        this.enabled = enabled;
    }

    /**
     * 为会话生成标题（仅当当前无标题且启用时）。
     *
     * @return 是否生成了标题
     */
    public boolean maybeTitle(SessionId sessionId, String firstUserMessage) {
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
            ChatOptions options = OpenAiChatOptions.builder()
                    .model(llmGateway.defaultModel())
                    .temperature(0.3)
                    .build();
            ChatResponse response = llmGateway.call(messages, options);
            String title = response.getResult().getOutput().getText();
            if (title != null && !title.isBlank()) {
                String cleaned = title.replace("\n", " ").trim();
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
