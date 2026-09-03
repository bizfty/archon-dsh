package com.bizfty.anchon.dsh.agent;

import com.bizfty.anchon.dsh.compaction.CompactionBoundaryStore;
import com.bizfty.anchon.dsh.compaction.CompactionService;
import com.bizfty.anchon.dsh.core.event.SessionEventBus;
import com.bizfty.anchon.dsh.core.event.SessionEventType;
import com.bizfty.anchon.dsh.core.model.SessionId;
import com.bizfty.anchon.dsh.core.model.SessionMessage;
import com.bizfty.anchon.dsh.session.SessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * durable 工具结果修剪编排（P2-②，见 docs/design-p2-hardening.md B 节）。
 * <p>
 * dsh-compaction 只做「选择 + 报告」（纯计算）；本组件（dsh-agent，同时依赖
 * dsh-session 消息 repository 与事件总线）执行 durable 化：把选定 TOOL 行标记
 * {@code pruned=true}（原文 content 保留，日志无损铁律）并发布
 * {@code TOOL_RESULT_PRUNE} 观测事件；此后投影层对该行输出确定性截断视图。
 * <p>
 * 触发：{@code dsh.compaction.prune-old-results.enabled=true} 时由外部/手动调用
 * （本组件不自动挂钩压缩压力路径 —— Java 压力估算已按读时截断视图计长，
 * durable 标记不改变模型可见 token 数，见 design-p2-hardening.md B 节差异说明）。
 */
@Service
public class ToolResultPruneService {

    private static final Logger log = LoggerFactory.getLogger(ToolResultPruneService.class);

    private final SessionService sessionService;
    private final CompactionService compactionService;
    /** 事件总线（直接 new 的测试场景可传 null → 不发布事件）。 */
    private final SessionEventBus eventBus;
    /** 压缩遮蔽边界存储（可空：无装配时从 0 起扫全部历史）。 */
    private final CompactionBoundaryStore compactionBoundaryStore;

    public ToolResultPruneService(SessionService sessionService,
                                  CompactionService compactionService,
                                  SessionEventBus eventBus) {
        this(sessionService, compactionService, eventBus, null);
    }

    @Autowired
    public ToolResultPruneService(SessionService sessionService,
                                  CompactionService compactionService,
                                  SessionEventBus eventBus,
                                  CompactionBoundaryStore compactionBoundaryStore) {
        this.sessionService = sessionService;
        this.compactionService = compactionService;
        this.eventBus = eventBus;
        this.compactionBoundaryStore = compactionBoundaryStore;
    }

    /**
     * 对 boundary 之后有效历史中「超阈值且配对完整」的 TOOL 结果执行 durable 修剪。
     *
     * @param sessionId 会话
     * @return 聚合报告（replacedCount / savedCodePoints）；开关关闭或无可修剪时返回 0 报告
     */
    public CompactionService.ToolResultPruneReport pruneOldToolResults(SessionId sessionId) {
        if (!compactionService.pruneOldResultsEnabled()) {
            return new CompactionService.ToolResultPruneReport(0, 0);
        }
        List<SessionMessage> history = sessionService.listMessages(sessionId);
        int boundary = compactionBoundaryStore == null ? 0 : compactionBoundaryStore.read(sessionId);
        List<SessionMessage> effective = history.subList(Math.min(boundary, history.size()), history.size());

        List<CompactionService.PruneCandidate> candidates = compactionService.selectPrunableToolResults(effective);
        for (CompactionService.PruneCandidate candidate : candidates) {
            int affected = sessionService.markToolResultPruned(sessionId, candidate.messageId());
            if (affected > 0 && eventBus != null) {
                eventBus.publish(sessionId, SessionEventType.TOOL_RESULT_PRUNE, payload(candidate));
            }
        }
        CompactionService.ToolResultPruneReport report = compactionService.toPruneReport(candidates);
        if (report.replacedCount() > 0) {
            log.info("[Prune] session={} durable 修剪 TOOL 结果 {} 条，省 {} 码点", sessionId.value(),
                    report.replacedCount(), report.savedCodePoints());
        }
        return report;
    }

    private static Map<String, Object> payload(CompactionService.PruneCandidate candidate) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("messageId", candidate.messageId());
        m.put("seq", candidate.seq());
        m.put("toolName", candidate.toolName());
        m.put("originalCodePoints", candidate.originalCodePoints());
        m.put("projectedCodePoints", candidate.projectedCodePoints());
        m.put("savedCodePoints", candidate.savedCodePoints());
        return m;
    }
}
