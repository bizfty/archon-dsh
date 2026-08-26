package com.bizfty.anchon.dsh.api;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SseExecutionStore 续流语义测试：事件缓冲、快照+订阅原子性（不丢窗口）、
 * 完成回调、已完成执行立即触发、清理。
 */
class SseExecutionStoreTest {

    @Test
    void appendBuffersAndFinishTriggersCallback() {
        SseExecutionStore store = new SseExecutionStore();
        SseExecutionStore.Execution exec = store.begin("exec-1");

        exec.append("message", java.util.Map.of("content", "你好"));
        exec.append("tool", java.util.Map.of("tool", "bash"));

        AtomicBoolean finished = new AtomicBoolean(false);
        exec.onFinish(() -> finished.set(true));
        assertFalse(finished.get(), "运行中注册完成回调不应立即触发");

        exec.finish();
        assertTrue(finished.get(), "finish 后完成回调应触发");
        assertFalse(exec.isRunning());
    }

    @Test
    void onFinishOnCompletedExecutionFiresImmediately() {
        SseExecutionStore store = new SseExecutionStore();
        SseExecutionStore.Execution exec = store.begin("exec-2");
        exec.append("message", java.util.Map.of("content", "done-already"));
        exec.finish();

        AtomicBoolean finished = new AtomicBoolean(false);
        exec.onFinish(() -> finished.set(true));
        assertTrue(finished.get(), "已完成执行注册完成回调应立即触发");
    }

    @Test
    void snapshotAndSubscribeSeesPriorEventsAndReceivesLaterOnes() {
        SseExecutionStore store = new SseExecutionStore();
        SseExecutionStore.Execution exec = store.begin("exec-3");

        exec.append("message", java.util.Map.of("content", "a"));
        exec.append("message", java.util.Map.of("content", "b"));

        List<String> received = new CopyOnWriteArrayList<>();
        List<SseExecutionStore.Event> snapshot = exec.snapshotAndSubscribe(
                event -> received.add((String) ((java.util.Map<?, ?>) event.data).get("content")));

        assertEquals(2, snapshot.size(), "快照应包含已发生事件");
        assertEquals("a", ((java.util.Map<?, ?>) snapshot.get(0).data).get("content"));

        // 快照+订阅之后的事件应实时到达（不丢窗口）
        exec.append("message", java.util.Map.of("content", "c"));
        exec.finish();
        assertEquals(List.of("c"), received, "订阅后事件应实时推送");
    }

    @Test
    void beginReturnsSameInstanceAndRemoveClears() {
        SseExecutionStore store = new SseExecutionStore();
        SseExecutionStore.Execution first = store.begin("exec-4");
        SseExecutionStore.Execution second = store.begin("exec-4");
        assertTrue(first == second, "重复 begin 应返回同一实例");

        store.remove("exec-4");
        assertNull(store.get("exec-4"), "remove 后应不可再取");
    }

    @Test
    void bufferIsBounded() {
        SseExecutionStore store = new SseExecutionStore();
        SseExecutionStore.Execution exec = store.begin("exec-5");
        for (int i = 0; i < 6000; i++) {
            exec.append("message", java.util.Map.of("content", "x"));
        }
        List<SseExecutionStore.Event> snapshot = exec.snapshotAndSubscribe(event -> { });
        assertTrue(snapshot.size() <= 5000, "缓冲应受限（超出丢弃最旧）");
    }
}
