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
        // 无标题会话默认以 session id 为标题（侧边栏/API 可识别）
        String effectiveTitle = (title == null || title.isBlank()) ? id.value() : title;
        Session session = new Session(id, effectiveTitle, model, cwd, now, now);
        sessionRepository.save(SessionEntity.from(session));
        return session;
    }

    /**
     * 建会话并预置种子消息（P2-④/D.4 CreateSessionOptions seeding）：先建会话行，
     * 再按序 append 种子（seq 从 1 单调）。seeds 为空时等价 {@link #createSession(String, String, String)}。
     * <p>
     * 种子只落消息 surface（日志事件由上层/后续 turn 自然产生）；REST 接入属产品决策，本层仅服务 API。
     */
    @Transactional
    public Session createSession(CreateSessionOptions options) {
        Session session = createSession(options.title(), options.model(), options.cwd());
        for (SeedMessage seed : options.seeds()) {
            append(session.id(), seed.role(), seed.content(), null, null, null);
        }
        return session;
    }

    /** 建会话选项（P2-④ seeding）：title/model/cwd + 种子消息列表。 */
    public record CreateSessionOptions(String title, String model, String cwd, List<SeedMessage> seeds) {
        public CreateSessionOptions {
            seeds = seeds == null ? List.of() : List.copyOf(seeds);
        }

        public static CreateSessionOptions of(String title, String model, String cwd) {
            return new CreateSessionOptions(title, model, cwd, List.of());
        }
    }

    /** 种子消息：角色 + 内容（P2-④ seeding）。 */
    public record SeedMessage(MessageRole role, String content) {
        public SeedMessage {
            if (role == null) {
                throw new IllegalArgumentException("seed role 不能为 null");
            }
            if (content == null) {
                throw new IllegalArgumentException("seed content 不能为 null");
            }
        }
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
        return saveSessionRow(entity).toDomain();
    }

    @Transactional
    public Session updateTitle(SessionId sessionId, String title) {
        SessionEntity entity = sessionRepository.findById(sessionId.value()).orElseThrow();
        entity.setTitle(title);
        entity.setUpdatedAt(Instant.now());
        return saveSessionRow(entity).toDomain();
    }

    /**
     * 会话行写回（P2-③ 写安全）：saveAndFlush 使乐观锁冲突在事务内显式抛出，
     * 包装为 {@link SessionConcurrentModificationException}（多实例并发更新会话行的防线；
     * 冲突由上层决定重试/告警，本服务不自动重试）。
     */
    private SessionEntity saveSessionRow(SessionEntity entity) {
        try {
            return sessionRepository.saveAndFlush(entity);
        } catch (org.springframework.dao.OptimisticLockingFailureException e) {
            // 兼容 ORM（ObjectOptimisticLockingFailureException）与 DAO 两层翻译产物
            throw new SessionConcurrentModificationException(entity.getId(), e);
        }
    }

    @Transactional(readOnly = true)
    public List<SessionMessage> listMessages(SessionId sessionId) {
        return messageRepository.findBySessionIdOrderBySeqAsc(sessionId.value())
                .stream().map(SessionMessageEntity::toDomain).toList();
    }

    /**
     * 把某条消息标记为 durable 已修剪（P2-②）：pruned=true 且原文 content 保留
     * （投影层对该 TOOL 行输出截断视图；日志无损）。仅当消息属于该会话时生效。
     *
     * @return 受影响行数（0 = 消息不存在或不属于该会话）
     */
    @Transactional
    public int markToolResultPruned(SessionId sessionId, String messageId) {
        return messageRepository.markPruned(messageId, sessionId.value());
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
        saveSessionRow(entity);
        return message;
    }

    /** 会话不存在。 */
    public static final class SessionNotFoundException extends RuntimeException {
        public SessionNotFoundException(SessionId sessionId) {
            super("会话不存在: " + sessionId);
        }
    }

    /** 会话行并发更新冲突（P2-③ 写安全：多实例同时写同一会话行，后提交者失败）。 */
    public static final class SessionConcurrentModificationException extends RuntimeException {
        public SessionConcurrentModificationException(String sessionId, Throwable cause) {
            super("会话并发更新冲突: " + sessionId + "（多实例写同一会话行，请上层重试或协调写所有权）", cause);
        }
    }
}
