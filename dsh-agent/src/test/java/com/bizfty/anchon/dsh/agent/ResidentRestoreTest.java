package com.bizfty.anchon.dsh.agent;

import com.bizfty.anchon.dsh.core.event.SessionEvent;
import com.bizfty.anchon.dsh.core.event.SessionEventBus;
import com.bizfty.anchon.dsh.core.event.SessionEventType;
import com.bizfty.anchon.dsh.core.model.SessionId;
import com.bizfty.anchon.dsh.session.SessionEventEntity;
import com.bizfty.anchon.dsh.session.SessionEventRepository;
import com.bizfty.anchon.dsh.util.JsonUtils;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * M4-5 phase 事件化持久化 + RestoreAgentRunner 重启恢复（design §4.3/§4.4，红线 §6-4b）：
 * phase 迁移发 AGENT_PHASE 事件；崩溃残留 running/queued/aborted 重启重建 → 置 aborted 停驻 +
 * executionId 回填（可 resume 同 id 续轮）；恢复对象可直接承接新执行。
 */
class ResidentRestoreTest {

    private final SessionId sessionId = SessionId.of("sess_restore");
    private final JsonUtils jsonUtils = new JsonUtils();

    private SessionEventEntity phaseEvent(String payloadJson) {
        return SessionEventEntity.from(SessionEvent.of(sessionId, SessionEventType.AGENT_PHASE, 7,
                jsonUtils.fromJson(payloadJson, Map.class)), payloadJson);
    }

    @Test
    void phaseTransitionsPublishAgentPhaseEvents() throws Exception {
        SessionEventBus bus = new SessionEventBus();
        List<SessionEventType> types = new ArrayList<>();
        List<Map<String, Object>> payloads = new ArrayList<>();
        Runnable disposer = bus.addListener(new com.bizfty.anchon.dsh.core.event.SessionEventListener() {
            @Override
            public int order() {
                return 0;
            }

            @Override
            public void onEvent(SessionEvent event) {
                types.add(event.type());
                payloads.add(event.payload());
            }
        });

        ResidentAgentRegistry registry = new ResidentAgentRegistry(bus);
        ResidentAgent agent = registry.agent(sessionId);
        try {
            agent.submit("t", () -> "ok").get(5, TimeUnit.SECONDS);
            // queued → running（任务完成回 IDLE 不发布）
            assertEquals(ResidentPhase.IDLE, agent.phase());
            assertTrue(types.contains(SessionEventType.AGENT_PHASE), "应有 AGENT_PHASE 事件，got=" + types);
            List<String> phases = payloads.stream()
                    .map(p -> String.valueOf(p.get("phase"))).toList();
            assertTrue(phases.contains("queued") && phases.contains("running"),
                    "应发布 queued→running 迁移，phases=" + phases);
            assertTrue(!phases.contains("idle"), "idle 不发布，phases=" + phases);

            agent.abort(); // 空闲 abort → aborted 事件
            assertTrue(payloads.stream().anyMatch(p -> "aborted".equals(p.get("phase"))),
                    "abort 应发布 aborted，phases=" + phases);
        } finally {
            disposer.run();
            registry.release(sessionId);
        }
    }

    @Test
    void restoreRebuildsAbortedObjectFromRunningLeftover() {
        SessionEventRepository repository = mock(SessionEventRepository.class);
        when(repository.findSessionIdsByEventType("AGENT_PHASE")).thenReturn(List.of(sessionId.value()));
        when(repository.findTop1BySessionIdAndEventTypeOrderBySeqDesc(sessionId.value(), "AGENT_PHASE"))
                .thenReturn(List.of(phaseEvent("{\"phase\":\"running\",\"executionId\":\"run-crash\"}")));

        ResidentAgentRegistry registry = new ResidentAgentRegistry();
        RestoreAgentRunner runner = new RestoreAgentRunner(registry, repository, jsonUtils);
        runner.run(null);

        ResidentAgent agent = registry.agent(sessionId);
        assertEquals(ResidentPhase.ABORTED, agent.phase(), "running 崩溃残留 → 重建并置 aborted");
        assertEquals("run-crash", agent.residentState().executionId(),
                "executionId 从事件回填（供同 id resume）");
        registry.release(sessionId);
    }

    @Test
    void restoreSkipsIdleAndUnknownSessions() {
        SessionEventRepository repository = mock(SessionEventRepository.class);
        // 无 AGENT_PHASE 会话 → 不创建任何对象
        when(repository.findSessionIdsByEventType("AGENT_PHASE")).thenReturn(List.of());
        ResidentAgentRegistry registry = new ResidentAgentRegistry();
        RestoreAgentRunner runner = new RestoreAgentRunner(registry, repository, jsonUtils);
        runner.run(null);
        assertTrue(registry.agents().isEmpty(), "无 phase 事件不恢复");

        // phase=error → 不恢复
        SessionId other = SessionId.of("sess_err");
        SessionEventRepository repo2 = mock(SessionEventRepository.class);
        when(repo2.findSessionIdsByEventType("AGENT_PHASE")).thenReturn(List.of(other.value()));
        when(repo2.findTop1BySessionIdAndEventTypeOrderBySeqDesc(other.value(), "AGENT_PHASE"))
                .thenReturn(List.of(SessionEventEntity.from(
                        SessionEvent.of(other, SessionEventType.AGENT_PHASE, 1,
                                jsonUtils.fromJson("{\"phase\":\"error\"}", Map.class)),
                        "{\"phase\":\"error\"}")));
        RestoreAgentRunner runner2 = new RestoreAgentRunner(registry, repo2, jsonUtils);
        runner2.run(null);
        assertTrue(!registry.agents().containsKey(other), "error 终态不恢复");
        registry.release(other);
        registry.release(sessionId);
    }

    @Test
    void restoredObjectAcceptsNewTurnCleanly() throws Exception {
        // 崩溃恢复（aborted 停驻 + executionId 回填）后，恢复对象可直接承接客户端重发/续轮的新执行
        SessionEventRepository repository = mock(SessionEventRepository.class);
        when(repository.findSessionIdsByEventType("AGENT_PHASE")).thenReturn(List.of(sessionId.value()));
        when(repository.findTop1BySessionIdAndEventTypeOrderBySeqDesc(sessionId.value(), "AGENT_PHASE"))
                .thenReturn(List.of(phaseEvent("{\"phase\":\"aborted\",\"executionId\":\"run-old\"}")));

        ResidentAgentRegistry registry = new ResidentAgentRegistry();
        RestoreAgentRunner runner = new RestoreAgentRunner(registry, repository, jsonUtils);
        runner.run(null);
        ResidentAgent agent = registry.agent(sessionId);
        assertEquals(ResidentPhase.ABORTED, agent.phase());

        CompletableFuture<String> next = agent.submit("resume-turn", () -> "恢复后正常执行");
        assertEquals("ok", next.get(5, TimeUnit.SECONDS).replace("恢复后正常执行", "ok"));
        assertEquals(ResidentPhase.IDLE, agent.phase(), "恢复对象承接新执行后回 idle");
        registry.release(sessionId);
    }
}
