package com.bizfty.anchon.dsh.interaction;

import java.util.List;

/**
 * 人机问题（对应 DSH interaction/user-questions 的问题模型）。
 */
public record UserQuestion(
        String id,
        String question,
        List<String> options,
        boolean multiSelect) {

    public static UserQuestion of(String id, String question, List<String> options, boolean multiSelect) {
        return new UserQuestion(id, question, options == null ? List.of() : options, multiSelect);
    }
}
