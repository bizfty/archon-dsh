package com.bizfty.anchon.dsh.session;

import com.bizfty.anchon.dsh.core.model.SessionId;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话投影校验器（M2，design §4.4）：对比 fact 重放与当前投影行，
 * 不一致（半写/损坏/缺失）则触发局部（按会话）重建自愈 —— 崩溃恢复 §6.3 的运行时入口。
 */
@Service
public class SessionProjectionVerifier {

    private final SessionMessageRepository messageRepository;
    private final SessionFactRepository factRepository;
    private final SessionFactReplayer replayer;

    public SessionProjectionVerifier(SessionMessageRepository messageRepository,
                                     SessionFactRepository factRepository,
                                     SessionFactReplayer replayer) {
        this.messageRepository = messageRepository;
        this.factRepository = factRepository;
        this.replayer = replayer;
    }

    /**
     * 列出该会话投影与 fact 的不一致原因（空列表 = 一致）。
     */
    @Transactional(readOnly = true)
    public List<String> diff(SessionId sessionId) {
        List<String> reasons = new ArrayList<>();
        List<SessionFactEntity> facts =
                factRepository.findBySessionIdOrderBySeqAsc(sessionId.value());
        List<SessionMessageEntity> messages =
                messageRepository.findBySessionIdOrderBySeqAsc(sessionId.value());
        if (facts.size() != messages.size()) {
            reasons.add("行数不一致: fact=" + facts.size() + " projection=" + messages.size());
        }
        Map<Long, SessionMessageEntity> bySeq = new HashMap<>();
        for (SessionMessageEntity m : messages) {
            bySeq.put(m.getSeq(), m);
        }
        for (SessionFactEntity f : facts) {
            SessionMessageEntity m = bySeq.get(f.getSeq());
            if (m == null) {
                reasons.add("投影缺 seq=" + f.getSeq());
                continue;
            }
            if (!equalsNullSafe(m.getRole(), f.getRole())) {
                reasons.add("seq=" + f.getSeq() + " role 不一致");
            }
            if (!equalsNullSafe(m.getContent(), f.getContent())) {
                reasons.add("seq=" + f.getSeq() + " content 不一致");
            }
            if (m.isPruned() != f.isPruned()) {
                reasons.add("seq=" + f.getSeq() + " pruned 不一致");
            }
        }
        return reasons;
    }

    /**
     * 检出不一致则按会话重建投影（自愈）。
     *
     * @return true = 检测到不一致并已重建
     */
    @Transactional
    public boolean repairIfMismatch(SessionId sessionId) {
        if (diff(sessionId).isEmpty()) {
            return false;
        }
        replayer.rebuild(sessionId);
        return true;
    }

    private static boolean equalsNullSafe(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }
}
