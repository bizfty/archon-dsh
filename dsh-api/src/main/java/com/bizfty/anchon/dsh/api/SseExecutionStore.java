package com.bizfty.anchon.dsh.api;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * SSE 执行事件存储 — 支持断线续流的执行级事件缓冲（对应官方 WebSocket 下行的事件流语义）。
 * <p>
 * 每个 SSE 执行（executionId）维护：已发生事件快照 + running 标志 + 续传订阅者。
 * 断线后客户端以同一 executionId 重连：
 * <ul>
 *   <li>执行仍在运行 → 重放快照后注册为续传订阅者，后续事件实时推送（不丢窗口：快照+订阅在同一锁内）；</li>
 *   <li>执行已完成 → 重放完整快照 + done，随后清理该执行。</li>
 * </ul>
 * 事件缓冲上限为每执行 {@link #MAX_EVENTS} 条，防止超长 turn 内存膨胀（超出只保留尾部）。
 */
@Component
public class SseExecutionStore {

    /** 每执行缓冲的事件上限（超出丢弃最旧；续流只对仍活跃的执行有意义）。 */
    private static final int MAX_EVENTS = 5000;

    private final Map<String, Execution> executions = new ConcurrentHashMap<>();

    /** 一条已发生的 SSE 事件（name + 可序列化 data）。 */
    public static final class Event {
        public final String name;
        public final Object data;

        public Event(String name, Object data) {
            this.name = name;
            this.data = data;
        }
    }

    /** 一个执行的运行态：缓冲 + running + 续传订阅者。 */
    public static final class Execution {
        private volatile boolean running = true;
        private final List<Event> events = new ArrayList<>();
        private final List<Consumer<Event>> continuations = new ArrayList<>();
        private final List<Runnable> finishCallbacks = new ArrayList<>();

        private Execution() {
        }

        /** 追加一条事件：写缓冲 + 通知所有续传订阅者（同一锁内，订阅者不丢事件）。 */
        synchronized void append(String name, Object data) {
            events.add(new Event(name, data));
            if (events.size() > MAX_EVENTS) {
                events.remove(0);
            }
            for (Consumer<Event> c : continuations) {
                try {
                    c.accept(new Event(name, data));
                } catch (Exception ignored) {
                    // 订阅者发送失败（连接断开）：忽略，等待自身清理
                }
            }
        }

        /**
         * 快照 + 注册续传订阅（同一锁内完成，避免快照与订阅之间的事件丢失窗口）。
         *
         * @return 已发生事件的不可变快照
         */
        synchronized List<Event> snapshotAndSubscribe(Consumer<Event> continuation) {
            continuations.add(continuation);
            return List.copyOf(events);
        }

        synchronized boolean isRunning() {
            return running;
        }

        /** 标记完成并触发完成回调（续传连接据此发送 done）。 */
        synchronized void finish() {
            running = false;
            List<Runnable> callbacks = List.copyOf(finishCallbacks);
            finishCallbacks.clear();
            for (Runnable r : callbacks) {
                try {
                    r.run();
                } catch (Exception ignored) {
                }
            }
        }

        /** 注册完成回调；已完成的执行立即触发。 */
        synchronized void onFinish(Runnable callback) {
            if (!running) {
                try {
                    callback.run();
                } catch (Exception ignored) {
                }
                return;
            }
            finishCallbacks.add(callback);
        }
    }

    /** 开始一个执行（重复 begin 返回既有实例）。 */
    public Execution begin(String executionId) {
        return executions.computeIfAbsent(executionId, k -> new Execution());
    }

    /** 取执行（不存在返回 null）。 */
    public Execution get(String executionId) {
        return executions.get(executionId);
    }

    /** 结束并移除一个执行（续流完成后的清理；活跃执行 finish 后仍保留供重放，由续流 done 后移除）。 */
    public void remove(String executionId) {
        executions.remove(executionId);
    }
}
