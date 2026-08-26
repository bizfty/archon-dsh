package com.bizfty.anchon.dsh.subagent;

import com.bizfty.anchon.dsh.agent.AgentLoopService;
import com.bizfty.anchon.dsh.agent.AgentRunRequest;
import com.bizfty.anchon.dsh.agent.AgentRunResult;
import com.bizfty.anchon.dsh.core.model.Session;
import com.bizfty.anchon.dsh.core.model.SessionId;
import com.bizfty.anchon.dsh.session.SessionService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 子代理运行器测试：深度守卫、生命周期、followup 续轮、注册表记录。
 */
class SubagentRunnerTest {

    private final SessionId parentId = SessionId.of("sess_parent");
    private final Session parent = new Session(parentId, "父", null, "/workspace",
            Instant.now(), Instant.now());

    private SubagentRunner runner(AgentLoopService loop, SessionService sessions, int maxDepth) {
        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<AgentLoopService> provider =
                mock(org.springframework.beans.factory.ObjectProvider.class);
        when(provider.getObject()).thenReturn(loop);
        return new SubagentRunner(provider, sessions, new SubagentRegistry(), maxDepth);
    }

    @Test
    void startsChildAndRegistersDoneHandle() {
        AgentLoopService loop = mock(AgentLoopService.class);
        SessionService sessions = mock(SessionService.class);
        when(sessions.getSession(parentId)).thenReturn(parent);
        when(sessions.createSession(any(), any(), any())).thenAnswer(inv -> new Session(
                SessionId.of("sess_child"), inv.getArgument(0), inv.getArgument(1),
                inv.getArgument(2), Instant.now(), Instant.now()));
        when(loop.run(any())).thenReturn(new AgentRunResult("子代理结果", SessionId.of("sess_child"), 1, 0));
        SubagentRegistry registry = new SubagentRegistry();
        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<AgentLoopService> provider =
                mock(org.springframework.beans.factory.ObjectProvider.class);
        when(provider.getObject()).thenReturn(loop);
        SubagentRunner runner = new SubagentRunner(provider, sessions, registry, 3);

        SubagentRunner.SubagentResult result = runner.start(parentId, "帮我查一下", 0, null);

        assertEquals("子代理结果", result.content());
        assertEquals(1, result.depth());
        assertTrue(result.childId().startsWith("sub_"));
        SubagentHandle handle = registry.get(parentId, result.childId());
        assertEquals(SubagentStatus.DONE, handle.status());
        assertEquals(1, handle.delegationDepth());
        assertEquals("子代理结果", handle.lastContent());
    }

    @Test
    void depthGuardRejects() {
        AgentLoopService loop = mock(AgentLoopService.class);
        SessionService sessions = mock(SessionService.class);
        when(sessions.getSession(parentId)).thenReturn(parent);
        SubagentRunner runner = runner(loop, sessions, 2);
        assertThrows(SubagentRunner.DepthExceededException.class,
                () -> runner.start(parentId, "任务", 2, null), "depth=2 达到上限 2 应拒绝");
    }

    @Test
    void followupContinuesSameChildSession() {
        AgentLoopService loop = mock(AgentLoopService.class);
        SessionService sessions = mock(SessionService.class);
        when(sessions.getSession(parentId)).thenReturn(parent);
        when(sessions.createSession(any(), any(), any())).thenAnswer(inv -> new Session(
                SessionId.of("sess_child"), inv.getArgument(0), inv.getArgument(1),
                inv.getArgument(2), Instant.now(), Instant.now()));
        AtomicInteger depth = new AtomicInteger();
        AtomicInteger callCount = new AtomicInteger();
        when(loop.run(any())).thenAnswer(inv -> {
            AgentRunRequest req = inv.getArgument(0);
            depth.set(req.delegationDepth());
            int n = callCount.incrementAndGet();
            return new AgentRunResult("第" + (n == 1 ? "一" : "二") + "轮回复",
                    req.sessionId(), 1, 0);
        });
        SubagentRegistry registry = new SubagentRegistry();
        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<AgentLoopService> provider =
                mock(org.springframework.beans.factory.ObjectProvider.class);
        when(provider.getObject()).thenReturn(loop);
        SubagentRunner runner = new SubagentRunner(provider, sessions, registry, 3);

        SubagentRunner.SubagentResult first = runner.start(parentId, "任务", 0, null);
        // followup 用同一子会话、同一深度
        var reply = runner.followup(parentId, first.childId(), "继续");
        assertEquals("第二轮回复", reply.orElse(""));
        assertEquals(1, depth.get(), "followup 深度应保持子代理深度 1");
        assertEquals(SubagentStatus.DONE, registry.get(parentId, first.childId()).status());
    }

    @Test
    void followupUnknownChildReturnsEmpty() {
        AgentLoopService loop = mock(AgentLoopService.class);
        SessionService sessions = mock(SessionService.class);
        when(sessions.getSession(parentId)).thenReturn(parent);
        SubagentRunner runner = runner(loop, sessions, 3);
        assertTrue(runner.followup(parentId, "sub_nope", "hi").isEmpty());
    }
}
