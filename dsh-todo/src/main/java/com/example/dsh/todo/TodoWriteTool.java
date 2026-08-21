package com.example.dsh.todo;

import com.example.dsh.tool.AgentTool;
import com.example.dsh.tool.Tool;
import com.example.dsh.tool.ToolCall;
import com.example.dsh.tool.ToolContext;
import com.example.dsh.tool.ToolResult;
import com.example.dsh.tool.ToolSchema;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * todo_write 工具 — 整表替换待办（对应 DSH packages/todo/tool-todo）。
 * <p>
 * 无部分更新：每次调用携带完整列表。合法状态校验失败即拒绝。
 */
@Tool(name = "todo_write",
      description = "写入当前任务的完整待办列表（整表替换，非增量）。todos 为 [{status, title, description}]，"
              + "status ∈ pending/in_progress/completed/cancelled/skipped。")
public class TodoWriteTool implements AgentTool {

    private final TodoStore todoStore;

    public TodoWriteTool(TodoStore todoStore) {
        this.todoStore = todoStore;
    }

    @Override
    public String name() {
        return "todo_write";
    }

    @Override
    public ToolSchema getSchema() {
        return ToolSchema.builder()
                .name(name())
                .description("写入完整待办列表（整表替换，非增量）。")
                .addObjectArrayParameter("todos", "待办项数组，每项含 status/title/description",
                        java.util.Map.of(
                                "status", new ToolSchema.Parameter("string", "状态: pending/in_progress/completed/cancelled/skipped，默认 pending", null, null, null),
                                "title", new ToolSchema.Parameter("string", "待办标题（必填）", null, null, null),
                                "description", new ToolSchema.Parameter("string", "详细说明", null, null, null)),
                        "title")
                .required("todos")
                .build();
    }

    @Override
    public ToolResult execute(ToolCall call, ToolContext context) {
        if (context.sessionId() == null) {
            return ToolResult.failure("缺少 sessionId");
        }
        List<Map<String, Object>> raw = call.getList("todos");
        if (raw == null) {
            // 兼容模型直接传字符串数组（如 ["任务A"] → pending 待办）
            List<String> rawStrings = call.getStringList("todos");
            if (rawStrings != null && !rawStrings.isEmpty()) {
                List<TodoItem> items = rawStrings.stream()
                        .filter(s -> s != null && !s.isBlank())
                        .map(s -> new TodoItem("pending", s, ""))
                        .toList();
                if (items.isEmpty()) {
                    return ToolResult.failure("缺少必要参数 todos");
                }
                todoStore.replace(context.sessionId(), items);
                return ToolResult.success("待办已更新: " + items.size() + " 项",
                        Map.of("total", items.size(), "pending", items.size()));
            }
            return ToolResult.failure("缺少必要参数 todos");
        }
        List<TodoItem> items = new ArrayList<>();
        List<String> invalid = new ArrayList<>();
        for (Map<String, Object> row : raw) {
            String status = row.get("status") == null ? "pending" : String.valueOf(row.get("status"));
            String title = row.get("title") == null ? "" : String.valueOf(row.get("title"));
            String desc = row.get("description") == null ? "" : String.valueOf(row.get("description"));
            if (title.isBlank()) {
                invalid.add("空标题");
                continue;
            }
            if (!TodoItem.isValidStatus(status)) {
                invalid.add("非法状态: " + status);
                continue;
            }
            items.add(new TodoItem(status, title, desc));
        }
        if (!invalid.isEmpty()) {
            return ToolResult.failure("todos 校验失败: " + String.join("; ", invalid));
        }
        todoStore.replace(context.sessionId(), items);
        long pending = items.stream().filter(i -> !"completed".equals(i.status()) && !"cancelled".equals(i.status())
                && !"skipped".equals(i.status())).count();
        return ToolResult.success("待办已更新: " + items.size() + " 项 (未完成 " + pending + " 项)",
                Map.of("total", items.size(), "pending", pending));
    }
}
