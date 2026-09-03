package com.bizfty.anchon.dsh.agent;

import com.bizfty.anchon.dsh.core.event.SessionEventType;
import com.bizfty.anchon.dsh.core.model.SessionId;
import com.bizfty.anchon.dsh.session.SessionEventEntity;
import com.bizfty.anchon.dsh.session.SessionEventRepository;
import com.bizfty.anchon.dsh.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 启动恢复执行态（M4-5，design §4.3）：进程重启后回读每会话最新 {@code AGENT_PHASE}
 * 事件（anchon_session_event），重建常驻 agent 执行对象 —— 执行真相由支柱① fact 自愈，
 * 执行态（对象/停驻/resume 身份）由本文恢复，两支柱互补。
 * <ul>
 *   <li>phase ∈ {running, queued}（崩溃残留，进程已死无对象在执行）→ 重建对象并置 aborted
 *       （停驻等用户 resume/新 chat；已落 fact 的 step 不重放）；</li>
 *   <li>phase = aborted（上次取消停驻）→ 重建对象并置 aborted（可 resume 同 executionId 续轮）；</li>
 *   <li>phase = idle/error 或从未有 phase 事件 → 不恢复（对象在下次 submit 时惰性创建）。</li>
 * </ul>
 * executionId 从事件回填对象身份（内存身份随崩溃丢失，事件承载之）→ resumeSession 可返回
 * 同 executionId 续轮身份。
 */
@Component
public class RestoreAgentRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RestoreAgentRunner.class);

    private final ResidentAgentRegistry registry;
    private final SessionEventRepository repository;
    private final JsonUtils jsonUtils;

    public RestoreAgentRunner(ResidentAgentRegistry registry,
                              SessionEventRepository repository,
                              JsonUtils jsonUtils) {
        this.registry = registry;
        this.repository = repository;
        this.jsonUtils = jsonUtils;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<String> sessionIds = repository.findSessionIdsByEventType(SessionEventType.AGENT_PHASE.name());
        if (sessionIds == null || sessionIds.isEmpty()) {
            return;
        }
        int restored = 0;
        for (String sessionIdValue : sessionIds) {
            List<SessionEventEntity> latest = repository.findTop1BySessionIdAndEventTypeOrderBySeqDesc(
                    sessionIdValue, SessionEventType.AGENT_PHASE.name());
            if (latest.isEmpty()) {
                continue;
            }
            PhaseRecord record = parse(latest.get(0));
            if (record == null || !isRestorable(record.phase())) {
                continue;
            }
            SessionId sessionId = SessionId.of(sessionIdValue);
            ResidentAgent agent = registry.agent(sessionId); // 重建执行者
            agent.abort();      // 置停驻 aborted（若 registry 挂总线会发布 AGENT_PHASE=aborted 覆盖残留态）
            agent.resetAbort(); // 停驻但不残留取消标志：resume/新 chat 直接可跑
            agent.recordIdentity(record.executionId(), "", "", ""); // 回填事件承载的 executionId
            log.info("[RestoreAgent] session={} 重建常驻执行者（残留 phase={}，置 aborted 等 resume）",
                    sessionIdValue, record.phase());
            restored++;
        }
        if (restored > 0) {
            log.info("[RestoreAgent] 启动恢复完成：重建 {} 个常驻执行者", restored);
        }
    }

    /** 可恢复 phase：running/queued（崩溃残留）与 aborted（取消停驻）。 */
    private static boolean isRestorable(String phase) {
        return "running".equals(phase) || "queued".equals(phase) || "aborted".equals(phase);
    }

    private record PhaseRecord(String phase, String executionId) {
    }

    @SuppressWarnings("unchecked")
    private PhaseRecord parse(SessionEventEntity event) {
        Map<String, Object> payload;
        if (event.getPayloadJson() == null || event.getPayloadJson().isBlank()) {
            payload = Map.of();
        } else {
            try {
                payload = jsonUtils.toMap(event.getPayloadJson());
            } catch (Exception e) {
                log.warn("[RestoreAgent] AGENT_PHASE payload 解析失败（忽略）: {}", e.getMessage());
                return null;
            }
        }
        Object p = payload.get("phase");
        if (p == null) {
            return null;
        }
        String executionId = payload.get("executionId") == null
                ? (event.getExecutionId() == null ? "" : event.getExecutionId())
                : String.valueOf(payload.get("executionId"));
        return new PhaseRecord(String.valueOf(p), executionId);
    }
}
