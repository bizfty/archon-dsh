package com.example.dsh.todo;

import com.example.dsh.core.model.SessionId;
import com.example.dsh.storage.StorageService;
import com.example.dsh.util.JsonUtils;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 待办存储 — 每会话整表，经 {@link StorageService} 持久化（JSON 文件后端下重启存活；
 * 对应 DSH todo/write 事件的全量快照语义）。
 */
@Component
public class TodoStore {

    private static final String NAMESPACE = "todo";

    private final StorageService storage;
    private final JsonUtils jsonUtils;

    public TodoStore(StorageService storage) {
        this.storage = storage;
        this.jsonUtils = new JsonUtils();
    }

    public List<TodoItem> get(SessionId sessionId) {
        return storage.get(NAMESPACE, sessionId.value())
                .map(v -> jsonUtils.fromJsonList(v, TodoItem.class))
                .orElse(List.of());
    }

    /** 整表替换（last-write-wins）。 */
    public void replace(SessionId sessionId, List<TodoItem> items) {
        storage.put(NAMESPACE, sessionId.value(), jsonUtils.toJson(items));
    }

    public boolean has(SessionId sessionId) {
        return storage.get(NAMESPACE, sessionId.value()).isPresent();
    }
}
