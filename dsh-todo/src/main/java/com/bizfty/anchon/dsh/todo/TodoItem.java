package com.bizfty.anchon.dsh.todo;

import java.util.List;

/**
 * 待办项（模型可见结构）— todo 是 DAG 的特殊形式（无依赖计划），
 * 该项直接映射到 plan_step：状态词汇与 DAG 步骤统一。
 */
public record TodoItem(String status, String title, String description) {

    /** 合法状态集合（与 DAG 步骤统一词汇）。 */
    public static final List<String> VALID_STATUSES =
            List.of("pending", "in_progress", "completed", "cancelled", "skipped", "failed");

    public static boolean isValidStatus(String status) {
        return VALID_STATUSES.contains(status);
    }
}
