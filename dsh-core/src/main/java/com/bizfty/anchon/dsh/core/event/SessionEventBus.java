package com.bizfty.anchon.dsh.core.event;

import com.bizfty.anchon.dsh.core.model.SessionId;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 会话事件总线 — 进程内事件分发（对应 DSH session/event 通知面）。
 * <p>
 * 注意：DSH 的 waterfall（可短路链）语义不在此处 — 需要短路/替换的扩展点
 * （tools/pre-execute、system-prompt/assemble）由各模块自建有序链。
 * 这里只做 observe-only 通知：持久化、投影、UI 推送、遥测。
 */
@Component
public class SessionEventBus {

    private final CopyOnWriteArrayList<SessionEventListener> listeners = new CopyOnWriteArrayList<>();
    private final AtomicLong seq = new AtomicLong(0);

    /** 注册监听器，返回 disposer。 */
    public Runnable addListener(SessionEventListener listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    public void addListener(Consumer<SessionEvent> consumer, int order) {
        addListener(new SessionEventListener() {
            @Override
            public void onEvent(SessionEvent event) {
                consumer.accept(event);
            }

            @Override
            public int order() {
                return order;
            }
        });
    }

    public void publish(SessionId sessionId, SessionEventType type, java.util.Map<String, Object> payload) {
        publish(SessionEvent.of(sessionId, type, seq.incrementAndGet(), payload));
    }

    public void publish(SessionEvent event) {
        List<SessionEventListener> snapshot = new ArrayList<>(listeners);
        snapshot.sort(Comparator.comparingInt(SessionEventListener::order));
        for (SessionEventListener listener : snapshot) {
            try {
                listener.onEvent(event);
            } catch (RuntimeException e) {
                // 事件监听器失败不阻断其它监听器（与 DSH "插件失败只结束当前扩展点"一致）
                System.getLogger("dsh.core.event").log(System.Logger.Level.WARNING,
                        "SessionEventListener failed for " + event.type(), e);
            }
        }
    }
}
