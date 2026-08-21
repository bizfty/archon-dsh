package com.example.dsh.schedule;

import com.example.dsh.core.event.SessionEventBus;
import com.example.dsh.core.event.SessionEventType;
import com.example.dsh.core.model.MessageRole;
import com.example.dsh.core.model.SessionId;
import com.example.dsh.session.SessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 会话内提醒服务（对应 DSH schedule：提醒内容作为用户消息写入会话日志 —
 * 拉取式循环在下次 turn 自然看到；冷恢复时日志即"补做"）。
 */
@Service
public class ScheduleService {

    private static final Logger log = LoggerFactory.getLogger(ScheduleService.class);

    private final SessionService sessionService;
    private final SessionEventBus eventBus;
    private final Map<String, ScheduleEntry> entries = new ConcurrentHashMap<>();
    private final ScheduledExecutorService executor =
            new ScheduledThreadPoolExecutor(1, r -> {
                Thread t = new Thread(r, "dsh-schedule");
                t.setDaemon(true);
                return t;
            });

    public ScheduleService(SessionService sessionService, SessionEventBus eventBus) {
        this.sessionService = sessionService;
        this.eventBus = eventBus;
    }

    /** 安排一个提醒（延迟毫秒后向会话写入消息）。 */
    public ScheduleEntry schedule(SessionId sessionId, long delayMs, String message) {
        ScheduleEntry entry = new ScheduleEntry("sched_" + UUID.randomUUID().toString().substring(0, 8),
                sessionId, message, Instant.now().plusMillis(delayMs), ScheduleEntry.Status.PENDING, Instant.now());
        entries.put(entry.id(), entry);
        executor.schedule(() -> fire(entry.id()), Math.max(0, delayMs), TimeUnit.MILLISECONDS);
        return entry;
    }

    private void fire(String entryId) {
        ScheduleEntry entry = entries.get(entryId);
        if (entry == null || entry.status() != ScheduleEntry.Status.PENDING) {
            return;
        }
        try {
            // 提醒写入会话日志（durable；下次 turn 作为用户消息出现在历史中）
            sessionService.append(entry.sessionId(), MessageRole.USER, entry.message(), null, null, null);
            eventBus.publish(entry.sessionId(), SessionEventType.USER_MESSAGE,
                    Map.of("content", entry.message(), "source", "schedule"));
            entries.put(entryId, entry.withStatus(ScheduleEntry.Status.FIRED));
            log.info("[Schedule] 提醒 {} 已触发: {}", entryId, entry.message());
        } catch (Exception e) {
            log.warn("[Schedule] 提醒 {} 触发失败: {}", entryId, e.getMessage());
        }
    }

    public List<ScheduleEntry> list(SessionId sessionId) {
        return entries.values().stream()
                .filter(e -> e.sessionId().equals(sessionId))
                .toList();
    }

    public Optional<ScheduleEntry> cancel(String entryId) {
        ScheduleEntry entry = entries.get(entryId);
        if (entry == null) {
            return Optional.empty();
        }
        entries.put(entryId, entry.withStatus(ScheduleEntry.Status.CANCELED));
        return Optional.of(entries.get(entryId));
    }

    /** 等待一个 PENDING 条目变为 FIRED（测试辅助）。 */
    public boolean awaitFired(String entryId, Duration timeout) {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            ScheduleEntry entry = entries.get(entryId);
            if (entry != null && entry.status() == ScheduleEntry.Status.FIRED) {
                return true;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }
}
