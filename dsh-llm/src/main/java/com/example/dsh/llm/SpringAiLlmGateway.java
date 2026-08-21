package com.example.dsh.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Spring AI ChatModel 网关实现（对应 DSH llm/llm-deepseek 的适配器面；
 * 底层模型由 spring-ai-starter-model-openai 自动配置，指向 DeepSeek 兼容端点）。
 * <p>
 * 按用户 API key 路由：{@link #call(List, ChatOptions, String)} 传入非空 key 时，
 * 用该 key + 同一 base-url 构建独立 {@link OpenAiChatModel}（按 key 缓存复用），
 * 对应 DSH 每 agent/会话可配 apiKey 的能力面；用户 profile 的 LLM key 经此注入。
 */
@Component
public class SpringAiLlmGateway implements LlmGateway {

    private static final Logger log = LoggerFactory.getLogger(SpringAiLlmGateway.class);

    private final ChatModel chatModel;
    private final String defaultModel;
    private final String baseUrl;
    private final ConcurrentHashMap<String, ChatModel> keyedModels = new ConcurrentHashMap<>();

    public SpringAiLlmGateway(ChatModel chatModel,
                              @Value("${spring.ai.openai.chat.options.model:deepseek-chat}") String defaultModel,
                              @Value("${spring.ai.openai.base-url:https://api.deepseek.com}") String baseUrl) {
        this.chatModel = chatModel;
        this.defaultModel = defaultModel;
        this.baseUrl = baseUrl;
    }

    @Override
    public ChatResponse call(List<Message> messages, ChatOptions options) {
        return chatModel.call(new Prompt(messages, options));
    }

    @Override
    public Flux<ChatResponse> stream(List<Message> messages, ChatOptions options) {
        return chatModel.stream(new Prompt(messages, options));
    }

    @Override
    public ChatResponse call(List<Message> messages, ChatOptions options, String apiKey) {
        return modelFor(apiKey).call(new Prompt(messages, options));
    }

    @Override
    public Flux<ChatResponse> stream(List<Message> messages, ChatOptions options, String apiKey) {
        return modelFor(apiKey).stream(new Prompt(messages, options));
    }

    @Override
    public String defaultModel() {
        return defaultModel;
    }

    /** 解析按 key 的 ChatModel：key 为空用默认模型，否则按 key 缓存独立模型。 */
    private ChatModel modelFor(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return chatModel;
        }
        return keyedModels.computeIfAbsent(apiKey, key -> {
            log.info("[Llm] 为独立 API key 构建 ChatModel（{} 个按 key 模型）", keyedModels.size() + 1);
            OpenAiChatOptions options = OpenAiChatOptions.builder()
                    .apiKey(key)
                    .baseUrl(baseUrl)
                    .model(defaultModel)
                    .build();
            return OpenAiChatModel.builder().options(options).build();
        });
    }

    /** 当前缓存的按 key 模型数（测试/观测用）。 */
    public int keyedModelCount() {
        return keyedModels.size();
    }
}
