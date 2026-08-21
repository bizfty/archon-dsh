package com.example.dsh.compaction;

import com.example.dsh.core.model.SessionId;
import com.example.dsh.storage.StorageService;
import org.springframework.stereotype.Component;

/**
 * 压缩遮蔽边界（shadow boundary）存储 — 对应 DSH compaction 的 surfaceOp replace 语义。
 * <p>
 * 压缩是**表面替换而非删日志**：摘要持久化为新 USER 消息后，被遮蔽的历史仍留在日志中。
 * 本存储记录"最新一次压缩遮蔽了多少条日志头"，使后续 turn 回放从摘要开始、
 * 不再重发已被摘要覆盖的历史（否则每次 turn 都重放完整旧头，token 压力无法收敛）。
 * <p>
 * 边界为绝对日志下标：回放起点 = max(边界, 长度 - 最大历史窗口)。
 */
@Component
public class CompactionBoundaryStore {

    private static final String NAMESPACE = "compaction-boundary";

    private final StorageService storage;

    public CompactionBoundaryStore(StorageService storage) {
        this.storage = storage;
    }

    /** 读取会话的遮蔽边界（无记录 → 0，表示无遮蔽）。 */
    public int read(SessionId sessionId) {
        return storage.get(NAMESPACE, sessionId.value())
                .map(Integer::parseInt)
                .orElse(0);
    }

    /** 写入会话的遮蔽边界（最新一次压缩遮蔽的日志头条数）。 */
    public void write(SessionId sessionId, int shadowedHeadCount) {
        if (shadowedHeadCount <= 0) {
            return;
        }
        storage.put(NAMESPACE, sessionId.value(), String.valueOf(shadowedHeadCount));
    }

    /** 会话结束时清除边界（可选）。 */
    public void clear(SessionId sessionId) {
        storage.delete(NAMESPACE, sessionId.value());
    }
}
