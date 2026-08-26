package com.bizfty.anchon.dsh.llm;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * LLM 网关 — 模型调用的唯一入口（对应 DSH llm/llm 的服务定义）。
 * <p>
 * 屏蔽底层 ChatModel；调用方只给消息列表 + 选项。
 * 工具调用**不**在此执行 — 返回的 ChatResponse 原样带工具调用，
 * 由 AgentLoopService 手动调度（与 DSH 的"重试/恢复点在 turn 级"一致）。
 */
public interface LlmGateway {

    /** 非流式调用。 */
    ChatResponse call(List<Message> messages, ChatOptions options);

    /** 流式调用。 */
    Flux<ChatResponse> stream(List<Message> messages, ChatOptions options);

    /**
     * 非流式调用（按用户 API key 路由）。
     *
     * @param apiKey 非空时用该 key 构建独立客户端（如用户 profile 的 LLM key）；
     *               null/空白时回落默认客户端（与 {@link #call(List, ChatOptions)} 相同）。
     */
    default ChatResponse call(List<Message> messages, ChatOptions options, String apiKey) {
        return call(messages, options);
    }

    /**
     * 流式调用（按用户 API key 路由），语义同 {@link #call(List, ChatOptions, String)}。
     */
    default Flux<ChatResponse> stream(List<Message> messages, ChatOptions options, String apiKey) {
        return stream(messages, options);
    }

    /** 默认模型名（配置或部署默认）。 */
    String defaultModel();
}
