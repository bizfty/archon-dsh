package com.bizfty.anchon.dsh.interaction;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 人机问答服务 — 聚合应答者；无应答者时返回结构化失败（模型自纠正）。
 */
@Service
public class UserQuestionService {

    private final ObjectProvider<UserQuestionProvider> providers;
    private final long defaultTimeoutMs;

    public UserQuestionService(ObjectProvider<UserQuestionProvider> providers,
                               @org.springframework.beans.factory.annotation.Value(
                                       "${dsh.interaction.user-question-timeout-ms:120000}") long defaultTimeoutMs) {
        this.providers = providers;
        this.defaultTimeoutMs = defaultTimeoutMs;
    }

    public String ask(String sessionId, String question, java.util.List<String> options, boolean multiSelect) {
        UserQuestion q = UserQuestion.of("q_" + UUID.randomUUID(), sessionId, question, options, multiSelect);
        var list = providers.orderedStream().toList();
        if (list.isEmpty()) {
            throw new NoAnswerProviderException("无问答应答者");
        }
        for (UserQuestionProvider provider : list) {
            try {
                java.util.Optional<String> answer = provider.ask(q, defaultTimeoutMs);
                if (answer.isPresent()) {
                    return answer.get();
                }
            } catch (Exception ignored) {
                // 尝试下一个应答者
            }
        }
        throw new NoAnswerProviderException("所有问答应答者均未应答");
    }

    /** 无应答者/超时 — 结构化失败，模型据此自纠正。 */
    public static final class NoAnswerProviderException extends RuntimeException {
        public NoAnswerProviderException(String message) {
            super(message);
        }
    }
}
