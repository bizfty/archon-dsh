package com.bizfty.anchon.dsh.agent;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 会话级取消协调 — 前端「停止生成」按钮的后端落地。
 * <p>
 * 每个会话至多一个并发 turn（前端保证）。cancel() 置位取消标志，
 * AgentLoop 在 step 循环检查并在模型/工具间隙停止，而不是中断线程
 * （工具调用可能正在写文件等副作用，中断不可靠；标志检查是协作式取消）。
 */
@Component
public class SessionCancellation {

    private final ConcurrentHashMap<String, AtomicBoolean> flags = new ConcurrentHashMap<>();

    /** 请求取消某会话的当前执行。 */
    public void cancel(String sessionId) {
        flags.computeIfAbsent(sessionId, k -> new AtomicBoolean()).set(true);
    }

    /** 该会话是否被请求取消。 */
    public boolean isCancelled(String sessionId) {
        AtomicBoolean f = flags.get(sessionId);
        return f != null && f.get();
    }

    /** 清除取消标志（每个 turn 开始时调用，保证新 turn 从干净状态开始）。 */
    public void clear(String sessionId) {
        flags.remove(sessionId);
    }
}
