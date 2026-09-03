package com.bizfty.anchon.dsh.agent;

import com.bizfty.anchon.dsh.core.event.SessionEvent;
import com.bizfty.anchon.dsh.core.event.SessionEventBus;
import com.bizfty.anchon.dsh.core.event.SessionEventType;
import com.bizfty.anchon.dsh.core.model.Session;
import com.bizfty.anchon.dsh.core.model.SessionId;
import com.bizfty.anchon.dsh.llm.LlmGateway;
import com.bizfty.anchon.dsh.llm.ModelCallEventPayloads;
import com.bizfty.anchon.dsh.session.SessionService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 会话标题生成测试：生成/跳过（已有标题）/禁用/失败不抛。
 */
class SessionTitleServiceTest {

    private final SessionId sessionId = SessionId.of("sess_title");
    private final Session session = new Session(sessionId, null, "deepseek-chat", null,
            Instant.now(), Instant.now());

    @Test
    void generatesAndPersistsTitle() {
        LlmGateway gateway = new TitleGateway("对话标题");
        SessionService sessions = mock(SessionService.class);
        when(sessions.getSession(sessionId)).thenReturn(session);
        SessionTitleService service = new SessionTitleService(gateway, sessions, true);

        boolean generated = service.maybeTitle(sessionId, "帮我写一个报告");

        assertTrue(generated);
        verify(sessions).updateTitle(sessionId, "对话标题");
    }

    @Test
    void skipsWhenAlreadyTitled() {
        LlmGateway gateway = new TitleGateway("对话标题");
        SessionService sessions = mock(SessionService.class);
        when(sessions.getSession(sessionId)).thenReturn(
                new Session(sessionId, "已有标题", "deepseek-chat", null, Instant.now(), Instant.now()));
        SessionTitleService service = new SessionTitleService(gateway, sessions, true);

        assertFalse(service.maybeTitle(sessionId, "hi"));
        verify(sessions, never()).updateTitle(any(), any());
    }

    @Test
    void disabledSkips() {
        SessionTitleService service = new SessionTitleService(new TitleGateway("x"),
                mock(SessionService.class), false);
        assertFalse(service.maybeTitle(sessionId, "hi"));
    }

    @Test
    void gatewayFailureDoesNotThrow() {
        LlmGateway failing = new LlmGateway() {
            @Override
            public ChatResponse call(List<Message> messages, ChatOptions options) {
                throw new IllegalStateException("mock failure");
            }

            @Override
            public Flux<ChatResponse> stream(List<Message> messages, ChatOptions options) {
                return Flux.error(new IllegalStateException("mock failure"));
            }

            @Override
            public String defaultModel() {
                return "deepseek-chat";
            }
        };
        SessionService sessions = mock(SessionService.class);
        when(sessions.getSession(sessionId)).thenReturn(session);
        SessionTitleService service = new SessionTitleService(failing, sessions, true);
        assertFalse(service.maybeTitle(sessionId, "hi"), "失败应忽略并返回 false");
    }

    @Test
    void trimsLongTitle() {
        AtomicInteger calls = new AtomicInteger();
        LlmGateway gateway = new LlmGateway() {
            @Override
            public ChatResponse call(List<Message> messages, ChatOptions options) {
                calls.incrementAndGet();
                return new ChatResponse(List.of(new Generation(
                        new AssistantMessage("x".repeat(100)))));
            }

            @Override
            public Flux<ChatResponse> stream(List<Message> messages, ChatOptions options) {
                return Flux.just(call(messages, options));
            }

            @Override
            public String defaultModel() {
                return "deepseek-chat";
            }
        };
        SessionService sessions = mock(SessionService.class);
        when(sessions.getSession(sessionId)).thenReturn(session);
        SessionTitleService service = new SessionTitleService(gateway, sessions, true);
        service.maybeTitle(sessionId, "hi");
        assertEquals(1, calls.get());
        verify(sessions).updateTitle(org.mockito.ArgumentMatchers.eq(sessionId),
                org.mockito.ArgumentMatchers.matches(".*…"));
    }

    @Test
    void emitsModelCallEventsForTitleGeneration() {
        LlmGateway gateway = new TitleGateway("对话标题");
        SessionService sessions = mock(SessionService.class);
        when(sessions.getSession(sessionId)).thenReturn(session);
        SessionEventBus bus = new SessionEventBus();
        List<SessionEvent> events = new ArrayList<>();
        bus.addListener(events::add);
        SessionTitleService service = new SessionTitleService(gateway, sessions, true, bus);

        boolean generated = service.maybeTitle(sessionId, "帮我写一个报告");

        assertTrue(generated);
        List<SessionEventType> types = events.stream().map(SessionEvent::type).toList();
        assertTrue(types.contains(SessionEventType.MODEL_REQUEST), "标题调用应发 MODEL_REQUEST");
        assertTrue(types.contains(SessionEventType.MODEL_RESPONSE), "标题调用应发 MODEL_RESPONSE");
        SessionEvent req = events.stream()
                .filter(e -> e.type() == SessionEventType.MODEL_REQUEST).findFirst().orElseThrow();
        assertEquals(ModelCallEventPayloads.CALL_SITE_SESSION_TITLE, req.payload().get("callSite"));
        assertFalse(req.payload().toString().contains("apiKey"));
        SessionEvent res = events.stream()
                .filter(e -> e.type() == SessionEventType.MODEL_RESPONSE).findFirst().orElseThrow();
        assertEquals(ModelCallEventPayloads.CALL_SITE_SESSION_TITLE, res.payload().get("callSite"));
        assertEquals("对话标题", res.payload().get("text"));
    }

    private static final class TitleGateway implements LlmGateway {
        private final String title;

        TitleGateway(String title) {
            this.title = title;
        }

        @Override
        public ChatResponse call(List<Message> messages, ChatOptions options) {
            return new ChatResponse(List.of(new Generation(new AssistantMessage(title))));
        }

        @Override
        public Flux<ChatResponse> stream(List<Message> messages, ChatOptions options) {
            return Flux.just(call(messages, options));
        }

        @Override
        public String defaultModel() {
            return "deepseek-chat";
        }
    }
}
