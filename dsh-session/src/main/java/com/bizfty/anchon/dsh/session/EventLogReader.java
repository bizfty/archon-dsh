package com.bizfty.anchon.dsh.session;

import com.bizfty.anchon.dsh.core.event.SessionEventType;
import com.bizfty.anchon.dsh.core.model.SessionId;
import com.bizfty.anchon.dsh.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 会话事件日志读取服务 — 事件表定向/容错读取（P2 深化 D.3 format-refusal 精神 + A.2 durable series 恢复）。
 * <p>
 * 解析失败的行跳过并告警（JPA 行级无法整体拒绝，取容错保全其余日志），不抛整体失败。
 * callSite 用字面量 {@code "agent_turn"} 对齐 {@code dsh-llm ModelCallEventPayloads.CALL_SITE_AGENT_TURN}
 * （本模块不依赖 dsh-llm，避免跨模块依赖）。
 */
@Component
public class EventLogReader {

    private static final Logger log = LoggerFactory.getLogger(EventLogReader.class);

    /** agent_turn callSite（对齐 ModelCallEventPayloads.CALL_SITE_AGENT_TURN）。 */
    private static final String CALL_SITE_AGENT_TURN = "agent_turn";
    /** durable 恢复最多回看最近 MODEL_REQUEST 条数（同 turn 多 step 同 header，取最近一条即可；多取防御辅助调用点夹心）。 */
    private static final int RECENT_MODEL_REQUEST_LIMIT = 5;

    private final SessionEventRepository repository;
    private final JsonUtils jsonUtils;

    public EventLogReader(SessionEventRepository repository, JsonUtils jsonUtils) {
        this.repository = repository;
        this.jsonUtils = jsonUtils;
    }

    /**
     * 最近一次 agent_turn MODEL_REQUEST 载荷中的 header 指纹（P2-① durable series 恢复用）。
     * 从新到旧回看最近 {@value #RECENT_MODEL_REQUEST_LIMIT} 条 MODEL_REQUEST，取第一条
     * callSite=agent_turn 且含 headerFingerprint 者；旧数据/无事件 → {@link Optional#empty()}
     * （调用方按 initial 处理，与 P2 前行为一致）。
     */
    /**
     * 读取某会话全部事件行（seq 升序，format-refusal 精神 / §3.4 读取容错）：
     * payload 不可解析或为空的行跳过并告警，不整体失败；返回可读事件行。
     * 供事件日志消费者（审计/回放/修复）安全读取，坏行不阻断其余日志。
     */
    public List<SessionEventEntity> readEvents(SessionId sessionId) {
        List<SessionEventEntity> all;
        try {
            all = repository.findBySessionIdOrderBySeqAsc(sessionId.value());
        } catch (RuntimeException e) {
            log.warn("[EventLog] 读取事件失败 session={}: {}", sessionId.value(), e.getMessage());
            return List.of();
        }
        List<SessionEventEntity> good = new java.util.ArrayList<>(all.size());
        for (SessionEventEntity event : all) {
            String json = event.getPayloadJson();
            if (json == null || json.isBlank()) {
                log.warn("[EventLog] 跳过空 payload 事件行 session={} seq={} type={} id={}",
                        sessionId.value(), event.getSeq(), event.getEventType(), event.getId());
                continue;
            }
            try {
                Object parsed = jsonUtils.fromJson(json, Map.class);
                if (parsed == null) {
                    // JsonUtils.toJson(null) 落库为 "null" → 反解为 null，属坏行
                    log.warn("[EventLog] 跳过空 payload 事件行 session={} seq={} type={} id={}",
                            sessionId.value(), event.getSeq(), event.getEventType(), event.getId());
                    continue;
                }
                good.add(event);
            } catch (RuntimeException e) {
                log.warn("[EventLog] 跳过不可解析事件行 session={} seq={} type={} id={}: {}",
                        sessionId.value(), event.getSeq(), event.getEventType(), event.getId(), e.getMessage());
            }
        }
        return good;
    }

    public Optional<String> lastAgentTurnHeaderFingerprint(SessionId sessionId) {
        List<SessionEventEntity> recent;
        try {
            recent = repository.findTop5BySessionIdAndEventTypeOrderBySeqDesc(
                    sessionId.value(), SessionEventType.MODEL_REQUEST.name());
        } catch (RuntimeException e) {
            log.warn("[EventLog] 查询最近 MODEL_REQUEST 失败 session={}: {}", sessionId.value(), e.getMessage());
            return Optional.empty();
        }
        for (SessionEventEntity event : recent) {
            if (event.getPayloadJson() == null || event.getPayloadJson().isBlank()) {
                continue;
            }
            try {
                Map<?, ?> payload = jsonUtils.fromJson(event.getPayloadJson(), Map.class);
                if (!CALL_SITE_AGENT_TURN.equals(payload.get("callSite"))) {
                    continue;
                }
                Object fp = payload.get("headerFingerprint");
                if (fp instanceof String headerFingerprint && !headerFingerprint.isBlank()) {
                    return Optional.of(headerFingerprint);
                }
                // 旧数据：agent_turn 但无指纹 → 无 durable 历史
                return Optional.empty();
            } catch (RuntimeException e) {
                // format-refusal 精神：坏行跳过并告警，不整体失败（§3.4 读取容错）
                log.warn("[EventLog] 跳过不可解析 MODEL_REQUEST 行 session={} seq={} id={}: {}",
                        sessionId.value(), event.getSeq(), event.getId(), e.getMessage());
            }
        }
        return Optional.empty();
    }
}
