package com.bizfty.anchon.dsh.core.event;

/**
 * 会话事件监听器 — 订阅 {@link SessionEventBus}。
 */
@FunctionalInterface
public interface SessionEventListener {

    void onEvent(SessionEvent event);

    /** 监听器优先级（越小越先）。默认 0。 */
    default int order() {
        return 0;
    }
}
