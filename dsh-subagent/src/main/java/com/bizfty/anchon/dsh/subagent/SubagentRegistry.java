package com.bizfty.anchon.dsh.subagent;

import com.bizfty.anchon.dsh.core.model.SessionId;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 子代理注册表 — parentSession → { childId → handle }。
 * <p>
 * 对应 DSH ctx.subagents 的进程内注册面：委托策略固定（子代理不能自我加宽），
 * 句柄生命周期由父会话持有。
 */
@Component
public class SubagentRegistry {

    private final Map<SessionId, Map<String, SubagentHandle>> children = new ConcurrentHashMap<>();

    public void register(SessionId parentSession, SubagentHandle handle) {
        children.computeIfAbsent(parentSession, k -> new ConcurrentHashMap<>())
                .put(handle.id(), handle);
    }

    public void update(SessionId parentSession, String childId, SubagentHandle handle) {
        Map<String, SubagentHandle> map = children.get(parentSession);
        if (map != null) {
            map.put(childId, handle);
        }
    }

    public SubagentHandle get(SessionId parentSession, String childId) {
        Map<String, SubagentHandle> map = children.get(parentSession);
        return map == null ? null : map.get(childId);
    }

    public List<SubagentHandle> list(SessionId parentSession) {
        Map<String, SubagentHandle> map = children.get(parentSession);
        return map == null ? List.of() : List.copyOf(map.values());
    }
}
