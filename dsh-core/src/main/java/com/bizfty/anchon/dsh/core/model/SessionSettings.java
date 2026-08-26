package com.bizfty.anchon.dsh.core.model;

/**
 * 会话级设置命名空间约定（session-scoped settings）。
 * <p>
 * 每会话一个 namespace（{@code session.<id>}），键 = 设置名：
 * <pre>
 *   storage.put("session.abc123", "plan-mode", json)            // 计划模式状态
 *   storage.put("session.abc123", "goal", json)                 // 会话目标
 *   storage.put("session.abc123", "compaction-boundary", "31")  // 压缩遮蔽边界
 * </pre>
 * 与全局配置（settings.&lt;ns&gt;.&lt;key&gt;）区分：会话设置按会话隔离，
 * 枚举某会话的全部设置用 {@code storage.keys("session.<id>")}，删除会话时一键清空。
 */
public final class SessionSettings {

    private SessionSettings() {
    }

    /** 会话设置命名空间前缀。 */
    public static final String PREFIX = "session.";

    /** 会话的 settings 命名空间（如 session.abc123）。 */
    public static String namespace(SessionId sessionId) {
        return PREFIX + sessionId.value();
    }

    /** 会话设置键：plan-mode（计划模式状态）。 */
    public static final String KEY_PLAN_MODE = "plan-mode";

    /** 会话设置键：goal（会话目标）。 */
    public static final String KEY_GOAL = "goal";

    /** 会话设置键：compaction-boundary（压缩遮蔽边界）。 */
    public static final String KEY_COMPACTION_BOUNDARY = "compaction-boundary";
}
