package com.bizfty.anchon.dsh.session;

import com.bizfty.anchon.dsh.core.event.SessionEvent;
import com.bizfty.anchon.dsh.core.event.SessionEventType;
import com.bizfty.anchon.dsh.core.model.SessionId;
import com.bizfty.anchon.dsh.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 中断 turn 修复器（C-③，对应 DSH session-persistence 的 crash recovery 语义）。
 * <p>
 * 崩溃日志以 open {@code TURN_START}（无 {@code TURN_END}/{@code TURN_ERROR}）结束。
 * 对齐上游：persistence 不截断已 durable 追加的事件，修复属启动路径 — 进程重启后
 * 扫描事件表，为每个「悬空 turn」追加一个合成
 * {@code TURN_END { finish: "interrupted", steps, tool_calls }}（唯一 loop 自身
 * 不发射的 finish 值，见 design-upstream-migration.md §3）。追加以普通批次落同一表。
 * <p>
 * 消息投影表（anchon_session_message）不修改：中断 turn 的已持久化消息保留在表面
 * （与上游一致 — 已 durable 的事件不删，resume 由后续 turn 继续），此修复只消除
 * 事件日志层面的孤儿执行序列，供审计/查询视图正确闭合。
 * <p>
 * 幂等：每会话只在「最后一个 TURN_START 之后无任何 TURN_END/TURN_ERROR」时追加一次；
 * 追加后再次启动不再命中。适用单实例部署 — 多实例共享事件表时需先引入写所有权
 * （SessionHandle 式，P2）。可通过 {@code dsh.session.repair-interrupted-turns=false} 关闭。
 */
@Component
public class InterruptedTurnRepairer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(InterruptedTurnRepairer.class);

    /** 唯一 loop 自身不发射的 finish 值（上游 TurnEndReason.interrupted）。 */
    public static final String FINISH_INTERRUPTED = "interrupted";

    private final SessionEventRepository repository;
    private final JsonUtils jsonUtils;
    private final boolean enabled;

    public InterruptedTurnRepairer(SessionEventRepository repository,
                                   JsonUtils jsonUtils,
                                   @Value("${dsh.session.repair-interrupted-turns:true}") boolean enabled) {
        this.repository = repository;
        this.jsonUtils = jsonUtils;
        this.enabled = enabled;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            return;
        }
        int repaired = repairAll();
        if (repaired > 0) {
            log.info("[Repair] 启动修复：为 {} 个中断 turn 追加 interrupted 封口", repaired);
        }
    }

    /**
     * 扫描并修复全部中断 turn。返回追加的 TURN_END 数（幂等：重复调用不再追加）。
     */
    @Transactional
    public int repairAll() {
        if (!enabled) {
            return 0;
        }
        Map<String, List<SessionEventEntity>> bySession = new LinkedHashMap<>();
        for (SessionEventEntity e : repository.findAll()) {
            bySession.computeIfAbsent(e.getSessionId(), k -> new ArrayList<>()).add(e);
        }
        int repaired = 0;
        for (Map.Entry<String, List<SessionEventEntity>> entry : bySession.entrySet()) {
            List<SessionEventEntity> events = entry.getValue().stream()
                    .sorted(Comparator.comparingLong(SessionEventEntity::getSeq))
                    .toList();
            SessionEventEntity openTurn = findOpenTurn(events);
            if (openTurn == null) {
                continue;
            }
            long nextSeq = nextFreeSeq(entry.getKey(), events);
            int steps = 0;
            int toolCalls = 0;
            for (SessionEventEntity e : events) {
                if (e.getSeq() <= openTurn.getSeq()) {
                    continue;
                }
                if (SessionEventType.STEP_START.name().equals(e.getEventType())) {
                    steps++;
                } else if (SessionEventType.TOOL_CALL.name().equals(e.getEventType())) {
                    toolCalls++;
                }
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("finish", FINISH_INTERRUPTED);
            payload.put("steps", steps);
            payload.put("tool_calls", toolCalls);
            payload.put("executionId", openTurn.getExecutionId() == null ? "" : openTurn.getExecutionId());
            SessionEvent closer = SessionEvent.of(SessionId.of(entry.getKey()), SessionEventType.TURN_END,
                    nextSeq, payload);
            repository.save(SessionEventEntity.from(closer, jsonUtils.toJson(payload)));
            log.info("[Repair] session={} 中断 turn 封口: executionId={} steps={} toolCalls={} → seq {}",
                    entry.getKey(), payload.get("executionId"), steps, toolCalls, nextSeq);
            repaired++;
        }
        return repaired;
    }

    /**
     * 找悬空 turn：最后一个 TURN_START，其后无 TURN_END/TURN_ERROR。无则返回 null。
     */
    private SessionEventEntity findOpenTurn(List<SessionEventEntity> events) {
        int lastTurnStart = -1;
        for (int i = 0; i < events.size(); i++) {
            String type = events.get(i).getEventType();
            if (SessionEventType.TURN_START.name().equals(type)) {
                lastTurnStart = i;
            }
        }
        if (lastTurnStart < 0) {
            return null;
        }
        for (int i = lastTurnStart + 1; i < events.size(); i++) {
            String type = events.get(i).getEventType();
            if (SessionEventType.TURN_END.name().equals(type)
                    || SessionEventType.TURN_ERROR.name().equals(type)) {
                return null; // 该 turn 已闭合
            }
        }
        return events.get(lastTurnStart);
    }

    /** 会话内最大 seq + 1，跳过 id 冲突（不同会话的 bus 全局 seq 可能留洞）。 */
    private long nextFreeSeq(String sessionId, List<SessionEventEntity> events) {
        long maxSeq = events.stream().mapToLong(SessionEventEntity::getSeq).max().orElse(0);
        long candidate = maxSeq + 1;
        while (true) {
            String id = SessionEventEntity.from(
                    SessionEvent.of(SessionId.of(sessionId), SessionEventType.TURN_END, candidate, Map.of()),
                    "{}").getId();
            if (!repository.existsById(id)) {
                return candidate;
            }
            candidate++;
        }
    }
}
