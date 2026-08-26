package com.bizfty.anchon.dsh.session;

import com.bizfty.anchon.dsh.core.model.MessageRole;
import com.bizfty.anchon.dsh.core.model.Session;
import com.bizfty.anchon.dsh.core.model.SessionId;
import com.bizfty.anchon.dsh.core.model.SessionMessage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 会话服务 — 会话与消息的持久化访问（对应 DSH session-persistence 的消费者面）。
 * <p>
 * 消息 seq 单调递增；append 后更新会话 updatedAt。
 */
@Service
public class SessionService {

    private final SessionRepository sessionRepository;
    private final SessionMessageRepository messageRepository;

    public SessionService(SessionRepository sessionRepository, SessionMessageRepository messageRepository) {
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
    }

    @Transactional
    public Session createSession(String title, String model, String cwd) {
        Instant now = Instant.now();
        SessionId id = SessionId.of("sess_" + UUID.randomUUID());
        Session session = new Session(id, title, model, cwd, now, now);
        sessionRepository.save(SessionEntity.from(session));
        return session;
    }

    @Transactional(readOnly = true)
    public Session getSession(SessionId sessionId) {
        return sessionRepository.findById(sessionId.value())
                .map(SessionEntity::toDomain)
                .orElseThrow(() -> new SessionNotFoundException(sessionId));
    }

    @Transactional(readOnly = true)
    public List<Session> listSessions() {
        return sessionRepository.findAll().stream().map(SessionEntity::toDomain).toList();
    }

    @Transactional
    public Session updateModel(SessionId sessionId, String model) {
        SessionEntity entity = sessionRepository.findById(sessionId.value()).orElseThrow();
        entity.setModel(model);
        entity.setUpdatedAt(Instant.now());
        return sessionRepository.save(entity).toDomain();
    }

    @Transactional
    public Session updateTitle(SessionId sessionId, String title) {
        SessionEntity entity = sessionRepository.findById(sessionId.value()).orElseThrow();
        entity.setTitle(title);
        entity.setUpdatedAt(Instant.now());
        return sessionRepository.save(entity).toDomain();
    }

    @Transactional(readOnly = true)
    public List<SessionMessage> listMessages(SessionId sessionId) {
        return messageRepository.findBySessionIdOrderBySeqAsc(sessionId.value())
                .stream().map(SessionMessageEntity::toDomain).toList();
    }

    @Transactional
    public SessionMessage append(SessionId sessionId, MessageRole role, String content,
                                 String toolCallId, String toolName, String toolCallsJson) {
        long seq = messageRepository.countBySessionId(sessionId.value()) + 1;
        SessionMessage message = new SessionMessage(
                "msg_" + UUID.randomUUID(), sessionId, role, content,
                toolCallId, toolName, toolCallsJson, seq, Instant.now());
        messageRepository.save(SessionMessageEntity.from(message));
        SessionEntity entity = sessionRepository.findById(sessionId.value()).orElseThrow();
        entity.setUpdatedAt(Instant.now());
        sessionRepository.save(entity);
        return message;
    }

    /** 会话不存在。 */
    public static final class SessionNotFoundException extends RuntimeException {
        public SessionNotFoundException(SessionId sessionId) {
            super("会话不存在: " + sessionId);
        }
    }
}
