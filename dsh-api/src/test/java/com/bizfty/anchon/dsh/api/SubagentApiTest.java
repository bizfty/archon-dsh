package com.bizfty.anchon.dsh.api;

import com.bizfty.anchon.dsh.subagent.SubagentHandle;
import com.bizfty.anchon.dsh.subagent.SubagentRegistry;
import com.bizfty.anchon.dsh.subagent.SubagentRunner;
import com.bizfty.anchon.dsh.subagent.SubagentStatus;
import com.bizfty.anchon.dsh.core.model.SessionId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 子代理 REST 端点测试：列表查询与继续对话。
 */
class SubagentApiTest {

    private final SessionId parentId = SessionId.of("sess_parent");

    private SessionController controller(SubagentRegistry registry, SubagentRunner runner) {
        com.bizfty.anchon.dsh.session.SessionService sessions = mock(com.bizfty.anchon.dsh.session.SessionService.class);
        com.bizfty.anchon.dsh.agent.AgentLoopService loop = mock(com.bizfty.anchon.dsh.agent.AgentLoopService.class);
        com.bizfty.anchon.dsh.core.event.SessionEventBus bus = new com.bizfty.anchon.dsh.core.event.SessionEventBus();
        com.bizfty.anchon.dsh.feedback.FeedbackService feedback = mock(com.bizfty.anchon.dsh.feedback.FeedbackService.class);
        return new SessionController(sessions, loop, bus, feedback, new SseExecutionStore(), registry, runner,
                new com.bizfty.anchon.dsh.agent.SessionCancellation());
    }

    @Test
    void subagentsEndpointListsChildren() {
        SubagentRegistry registry = new SubagentRegistry();
        registry.register(parentId, new SubagentHandle("sub_123", SessionId.of("sess_child"),
                1, SubagentStatus.DONE, "结果", Instant.now()));
        SessionController controller = controller(registry, mock(SubagentRunner.class));

        List<Map<String, Object>> list = controller.subagents("sess_parent");

        assertEquals(1, list.size());
        assertEquals("sub_123", list.get(0).get("id"));
        assertEquals("DONE", list.get(0).get("status"));
        assertEquals("sess_child", list.get(0).get("sessionId"));
        assertEquals("结果", list.get(0).get("lastContent"));
    }

    @Test
    void subagentsEndpointEmptyWhenNone() {
        SessionController controller = controller(new SubagentRegistry(), mock(SubagentRunner.class));
        assertTrue(controller.subagents("sess_none").isEmpty());
    }

    @Test
    void subagentMessageEndpointContinuesConversation() throws Exception {
        SubagentRunner runner = mock(SubagentRunner.class);
        when(runner.followup(eq(SessionId.of("sess_parent")), eq("sub_123"), eq("继续"))).thenReturn(
                java.util.Optional.of("子代理回复"));
        SessionController controller = controller(new SubagentRegistry(), runner);

        var resp = controller.subagentMessage("sess_parent", "sub_123", Map.of("message", "继续"));

        assertNotNull(resp.getBody());
        assertEquals("子代理回复", resp.getBody().get("reply"));
        assertEquals("sub_123", resp.getBody().get("childId"));
    }

    @Test
    void subagentMessageEndpointRejectsBlank() {
        SessionController controller = controller(new SubagentRegistry(), mock(SubagentRunner.class));
        var resp = controller.subagentMessage("sess_parent", "sub_123", Map.of("message", "  "));
        assertEquals(400, resp.getStatusCode().value());
    }

    @Test
    void subagentMessageEndpointNotFoundWhenUnknownChild() {
        SubagentRunner runner = mock(SubagentRunner.class);
        when(runner.followup(any(), any(), any())).thenReturn(java.util.Optional.empty());
        SessionController controller = controller(new SubagentRegistry(), runner);
        var resp = controller.subagentMessage("sess_parent", "sub_nope", Map.of("message", "hi"));
        assertEquals(404, resp.getStatusCode().value());
    }
}