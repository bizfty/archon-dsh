package com.bizfty.anchon.dsh.session;

import com.bizfty.anchon.dsh.core.model.MessageRole;
import com.bizfty.anchon.dsh.core.model.SessionId;
import com.bizfty.anchon.dsh.core.model.SessionMessage;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * 会话消息事实存储（M2 事件为真，design §4.2）：<b>单事务</b>写
 * (1) 事实事件 {@code anchon_session_fact}（append-only 真相）+
 * (2) 同事务物化投影行 {@code anchon_session_message}（读路径/搜索不变）+
 * (3) 会话行 updatedAt —— 关闭历史"消息行(事务A)+审计事件(REQUIRES_NEW 事务B)"的双写缺口。
 * <p>
 * seq 分配：fact 表 {@code count(sessionId)+1}；并发防线——
 * 第一道：本事务内 {@code saveAndFlush} 会话行乐观锁，冲突整体回滚并抛
 * {@link SessionService.SessionConcurrentModificationException}（与 SessionService 现状契约一致）；
 * 第二道：fact 表 UK(session_id,seq)（0007）。
 * <p>
 * 审计事件流 {@code anchon_session_event} 与本存储无关（发布点保留在调用方，职责分离）。
 */
@Service
public class SessionFactStore {

    private final SessionRepository sessionRepository;
    private final SessionMessageRepository messageRepository;
    private final SessionFactRepository factRepository;

    public SessionFactStore(SessionRepository sessionRepository,
                            SessionMessageRepository messageRepository,
                            SessionFactRepository factRepository) {
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.factRepository = factRepository;
    }

    /**
     * 追加一条消息事实（同事务写 fact + 投影行 + 会话行）。
     *
     * @return 物化出的消息投影（与消息行同构；m2-3 起供 SessionService.append 委托返回）
     */
    @Transactional
    public SessionMessage append(SessionId sessionId, MessageRole role, String content,
                                 String toolCallId, String toolName, String toolCallsJson,
                                 String metaJson) {
        Instant now = Instant.now();

        // 第一道防线（序列化点）：先碰会话行乐观锁并 saveAndFlush（独占该会话写权直至提交）。
        // 冲突即整体回滚（事务内尚未插任何行）；通过后同会话其它并发 append 的 gate 将阻塞，
        // 直到本事务提交才放行（届时其乐观锁版本已过期 → 显式 SessionConcurrentModificationException）。
        SessionEntity entity = sessionRepository.findById(sessionId.value())
                .orElseThrow(() -> new SessionService.SessionNotFoundException(sessionId));
        entity.setUpdatedAt(now);
        saveSessionRow(entity);

        // gate 之后 count 才稳定（行锁已持有）：同会话并发不会读到陈旧 count 导致重复 seq。
        long seq = factRepository.countBySessionId(sessionId.value()) + 1;

        String factId = "fact_" + UUID.randomUUID();
        factRepository.save(SessionFactEntity.of(factId, sessionId.value(), seq,
                role.name(), content, toolCallId, toolName, toolCallsJson, metaJson, now));

        String messageId = "msg_" + UUID.randomUUID();
        SessionMessage message = new SessionMessage(messageId, sessionId, role, content,
                toolCallId, toolName, toolCallsJson, seq, now);
        messageRepository.save(SessionMessageEntity.from(message));
        return message;
    }

    /**
     * durable 修剪：同事务把指定消息对应的 fact 行与投影行都置 pruned=true
     * （fact 保留原文，语义与现状一致；幂等）。
     *
     * @return 受影响消息行数（0 = 消息不存在或不属于该会话）
     */
    @Transactional
    public int markPruned(SessionId sessionId, String messageId) {
        return messageRepository.findById(messageId)
                .filter(msg -> msg.getSessionId().equals(sessionId.value()))
                .map(msg -> {
                    factRepository.findBySessionIdAndSeq(sessionId.value(), msg.getSeq())
                            .ifPresent(fact -> fact.setPruned(true));
                    return messageRepository.markPruned(msg.getId(), sessionId.value());
                })
                .orElse(0);
    }

    /**
     * 只读重放（fact-replay 验证读，design §4.5）：从真相 {@code anchon_session_fact} 按
     * {@code (sessionId, seq)} 升序直接映射为消息视图，不触碰投影行。供 M2-5 一致性对照
     * （§6.4：fact-replay 与 table 读结果一致）。消息 id 为派生稳定 id（{@code replay_<h>_<seq>}，
     * 非投影行 id）——本读路径仅验证用，不承载依赖投影行 id 的写侧（修剪/压缩）。
     */
    @Transactional(readOnly = true)
    public java.util.List<SessionMessage> listFromFact(SessionId sessionId) {
        return factRepository.findBySessionIdOrderBySeqAsc(sessionId.value()).stream()
                .map(f -> new SessionMessage(
                        "replay_" + Math.abs(sessionId.value().hashCode()) + "_" + f.getSeq(),
                        sessionId,
                        MessageRole.valueOf(f.getRole()),
                        f.getContent(),
                        f.getToolCallId(),
                        f.getToolName(),
                        f.getToolCallsJson(),
                        f.getSeq(),
                        f.getCreatedAt(),
                        f.isPruned()))
                .toList();
    }

    /** 会话行写回：saveAndFlush 使乐观锁冲突在事务内显式抛出并包装（契约同 SessionService）。 */
    private void saveSessionRow(SessionEntity entity) {
        try {
            sessionRepository.saveAndFlush(entity);
        } catch (OptimisticLockingFailureException e) {
            throw new SessionService.SessionConcurrentModificationException(entity.getId(), e);
        }
    }

}
