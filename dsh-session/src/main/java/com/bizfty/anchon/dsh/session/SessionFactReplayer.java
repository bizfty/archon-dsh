package com.bizfty.anchon.dsh.session;

import com.bizfty.anchon.dsh.core.model.SessionId;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 会话投影幂等重建器（M2，design §4.4）：以 {@code anchon_session_fact} 为真相，
 * 重放重建投影 {@code anchon_session_message}。投影是可丢弃缓存 —— 采用"删该会话投影 +
 * 按 fact 全量重建"策略，天然幂等（结果与已存在投影无关，多次重跑一致）。
 */
@Service
public class SessionFactReplayer {

    private final SessionMessageRepository messageRepository;
    private final SessionFactRepository factRepository;

    public SessionFactReplayer(SessionMessageRepository messageRepository,
                               SessionFactRepository factRepository) {
        this.messageRepository = messageRepository;
        this.factRepository = factRepository;
    }

    /**
     * 重建某会话的完整投影。
     *
     * @return 重建后的投影消息行数
     */
    @Transactional
    public int rebuild(SessionId sessionId) {
        List<SessionFactEntity> facts =
                factRepository.findBySessionIdOrderBySeqAsc(sessionId.value());
        messageRepository.deleteBySessionId(sessionId.value());
        for (SessionFactEntity f : facts) {
            SessionMessageEntity m = new SessionMessageEntity();
            m.setId("msg_replay_" + sessionId.value().hashCode() + "_" + f.getSeq() + "_" + java.util.UUID.randomUUID());
            m.setSessionId(sessionId.value());
            m.setSeq(f.getSeq());
            m.setRole(f.getRole());
            m.setContent(f.getContent());
            m.setToolCallId(f.getToolCallId());
            m.setToolName(f.getToolName());
            m.setToolCallsJson(f.getToolCallsJson());
            m.setPruned(f.isPruned());
            m.setCreatedAt(f.getCreatedAt());
            messageRepository.save(m);
        }
        return facts.size();
    }
}
