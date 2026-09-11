package com.bizfty.anchon.dsh.plantask;

import com.bizfty.anchon.dsh.storage.StorageService;
import com.bizfty.anchon.dsh.util.JsonUtils;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 计划任务存储（对应 DSH plan-task 的任务池持久化）。
 * <p>
 * 复用统一 KV（{@link StorageService}，表 anchon_kv / 文件后端），按工作区分 namespace：
 * namespace = {@code plan-task:{workspaceId}}，key = {@code task:{id}}，value = PlanTask JSON。
 * 重启后任务与状态保留。
 */
@Component
public class TaskStore {

    /** 命名空间前缀。 */
    private static final String NS_PREFIX = "plan-task:";

    private final StorageService storage;
    private final JsonUtils jsonUtils;

    public TaskStore(StorageService storage) {
        this.storage = storage;
        this.jsonUtils = new JsonUtils();
    }

    private static String namespace(String workspaceId) {
        return NS_PREFIX + workspaceId;
    }

    private static String key(String taskId) {
        return "task:" + taskId;
    }

    public PlanTask save(String workspaceId, PlanTask task) {
        storage.put(namespace(workspaceId), key(task.id()), jsonUtils.toJson(task));
        return task;
    }

    public Optional<PlanTask> get(String workspaceId, String taskId) {
        return storage.get(namespace(workspaceId), key(taskId))
                .map(v -> jsonUtils.fromJson(v, PlanTask.class));
    }

    public void delete(String workspaceId, String taskId) {
        storage.delete(namespace(workspaceId), key(taskId));
    }

    /** 某工作区全部任务（按 createdAt 升序）。 */
    public List<PlanTask> list(String workspaceId) {
        String ns = namespace(workspaceId);
        return storage.keys(ns).stream()
                .filter(k -> k.startsWith("task:"))
                .map(k -> storage.get(ns, k).map(v -> jsonUtils.fromJson(v, PlanTask.class)))
                .flatMap(Optional::stream)
                .sorted(Comparator.comparingLong(PlanTask::createdAt))
                .toList();
    }

    /** 生成新任务 id。 */
    public static String newId() {
        return "task-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
