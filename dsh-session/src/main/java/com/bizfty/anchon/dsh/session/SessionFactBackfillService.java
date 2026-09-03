package com.bizfty.anchon.dsh.session;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 存量会话 fact 快照回填（M2 数据迁移，design §4.5 R3 / §10 存量回填）：
 * 对启用"事件为真"写路径前已存在消息行的会话，把 {@code anchon_session_message}
 * 按 (session, seq) 升序逐条快照为 {@code anchon_session_fact}，保证 fact 自 seq=1 连续、
 * 幂等（缺哪条补哪条；重跑不重复）。meta_json 置空（legacy 无法区分压缩摘要来源，
 * compacted 标注自 m2-3 起由新写路径负责）。
 * <p>
 * 执行时序：0007 建表后、m2-3 写路径切换前运行一次（生产为发布步骤之一）。
 */
@Service
public class SessionFactBackfillService {

    private final SessionMessageRepository messageRepository;
    private final SessionFactRepository factRepository;

    public SessionFactBackfillService(SessionMessageRepository messageRepository,
                                      SessionFactRepository factRepository) {
        this.messageRepository = messageRepository;
        this.factRepository = factRepository;
    }

    /**
     * 执行全量幂等回填。
     *
     * @return 本次实际插入的 fact 行数
     */
    @Transactional
    public int backfill() {
        List<SessionMessageEntity> messages = messageRepository.findAll();
        // 按 (sessionId, seq) 升序
        messages.sort(java.util.Comparator
                .comparing(SessionMessageEntity::getSessionId)
                .thenComparingLong(SessionMessageEntity::getSeq));
        int inserted = 0;
        for (SessionMessageEntity m : messages) {
            if (factRepository.findBySessionIdAndSeq(m.getSessionId(), m.getSeq()).isPresent()) {
                continue; // 幂等：该 (session,seq) 已有 fact，跳过
            }
            SessionFactEntity fact = SessionFactEntity.of(
                    "fact_bf_" + java.util.UUID.randomUUID(),
                    m.getSessionId(), m.getSeq(), m.getRole(), m.getContent(),
                    m.getToolCallId(), m.getToolName(), m.getToolCallsJson(),
                    null, m.getCreatedAt() == null ? java.time.Instant.now() : m.getCreatedAt());
            fact.setPruned(m.isPruned()); // 回填保留 durable 修剪标记
            factRepository.save(fact);
            inserted++;
        }
        return inserted;
    }

    /**
     * 一致性自检：返回 fact 数与 message 数不一致的会话（期望空列表）。
     */
    @Transactional(readOnly = true)
    public List<String> findInconsistentSessions() {
        List<SessionMessageEntity> messages = messageRepository.findAll();
        java.util.Map<String, Long> msgCount = new java.util.HashMap<>();
        for (SessionMessageEntity m : messages) {
            msgCount.merge(m.getSessionId(), 1L, Long::sum);
        }
        java.util.Map<String, Long> factCount = new java.util.HashMap<>();
        for (SessionFactEntity f : factRepository.findAll()) {
            factCount.merge(f.getSessionId(), 1L, Long::sum);
        }
        return msgCount.keySet().stream()
                .filter(id -> !factCount.getOrDefault(id, 0L).equals(msgCount.get(id)))
                .sorted().toList();
    }
}
