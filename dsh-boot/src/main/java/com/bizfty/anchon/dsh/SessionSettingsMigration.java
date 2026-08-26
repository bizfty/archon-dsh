package com.bizfty.anchon.dsh;

import com.bizfty.anchon.dsh.storage.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 一次性迁移：旧会话级 KV 命名空间 → 会话级设置命名空间（session.&lt;id&gt;）。
 * <pre>
 *   plan.<id>                 → session.<id>/plan-mode
 *   goals.<id>                → session.<id>/goal
 *   compaction-boundary.<id>  → session.<id>/compaction-boundary
 * </pre>
 * 幂等：目标键已存在则跳过；迁移完成后删除旧键。
 */
@Component
public class SessionSettingsMigration implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SessionSettingsMigration.class);

    private final StorageService storage;

    public SessionSettingsMigration(StorageService storage) {
        this.storage = storage;
    }

    @Override
    public void run(ApplicationArguments args) {
        int moved = 0;
        moved += move("plan", "plan-mode");
        moved += move("goals", "goal");
        moved += move("compaction-boundary", "compaction-boundary");
        if (moved > 0) {
            log.info("[SessionSettings] 迁移完成: {} 个会话级设置键 → session.<id> 命名空间", moved);
        }
    }

    /** 把旧命名空间的每个键搬到会话级设置命名空间。 */
    private int move(String oldNamespace, String settingKey) {
        int moved = 0;
        for (String sessionId : storage.keys(oldNamespace)) {
            var value = storage.get(oldNamespace, sessionId);
            if (value.isEmpty()) {
                continue;
            }
            String target = "session." + sessionId;
            if (storage.get(target, settingKey).isPresent()) {
                // 已迁移过（目标已存在）→ 清理旧键
                storage.delete(oldNamespace, sessionId);
                continue;
            }
            storage.put(target, settingKey, value.get());
            storage.delete(oldNamespace, sessionId);
            moved++;
            log.info("[SessionSettings] {} {} → session.{}/{}", oldNamespace, sessionId, sessionId, settingKey);
        }
        return moved;
    }
}
