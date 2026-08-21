package com.example.dsh.todo;

import java.util.List;

/**
 * 待办项（模型可见结构）。
 */
public record TodoItem(String status, String title, String description) {

    /** 合法状态集合（对应 DSH todo 状态词汇）。 */
    public static final List<String> VALID_STATUSES =
            List.of("pending", "in_progress", "completed", "cancelled", "skipped");

    public static boolean isValidStatus(String status) {
        return VALID_STATUSES.contains(status);
    }
}
