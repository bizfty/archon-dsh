package com.example.dsh.interaction;

/**
 * 人机问答应答者 SPI（对应 DSH ctx.userQuestions 的 provider 缝）。
 */
public interface UserQuestionProvider {

    String name();

    /**
     * 向用户提问并等待应答。
     *
     * @return 应答文本；无法处理返回 empty
     */
    java.util.Optional<String> ask(UserQuestion question, long timeoutMs);
}
