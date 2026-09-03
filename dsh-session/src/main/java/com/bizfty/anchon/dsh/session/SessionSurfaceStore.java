package com.bizfty.anchon.dsh.session;

import com.bizfty.anchon.dsh.core.event.SessionEventBus;
import com.bizfty.anchon.dsh.core.event.SessionEventType;
import com.bizfty.anchon.dsh.core.model.SessionId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.UUID;

/**
 * 表面指令写入存储（M8 surfaceOp，docs/design-surface-op.md §4.4）— {@code anchon_session_surface} 的
 * 唯一写入口。
 * <p>
 * 语义：在不可变消息真相之上叠加"可见面显式演进"——指令 append-only 累积（不回改历史指令）；
 * 每次写入分配会话内单调 {@code gen}（可见面代数）。与 {@link SessionFactStore} 相同的写安全模式：
 * 先碰会话行乐观锁（saveAndFlush gate，独占该会话写权直至提交）再 count+1 分配 gen —— 并发写
 * 不会错序（后提交者抛 {@link SessionService.SessionConcurrentModificationException}）；
 * DB 层 {@code uk_anchon_surface_session_gen} 为第二道防线。
 * <p>
 * 不触碰 fact/消息投影行（surface 只作用于模型可见视图，不删行）；写后发
 * {@link SessionEventType#SESSION_SURFACE_CHANGED} 观测事件（observe-only，供审计/前端），并直失效
 * {@link SessionProjectionRegistry}（M9 投影 registry 化缓存，D3-C 双通道主通道 —— 写后下一读必重载，
 * 无陈旧窗口；事件监听为冗余兜底）。
 */
@Service
public class SessionSurfaceStore {

    private static final Logger log = LoggerFactory.getLogger(SessionSurfaceStore.class);

    private final SessionRepository sessionRepository;
    private final SessionSurfaceRepository surfaceRepository;
    private final SessionEventBus eventBus;
    private final SessionProjectionRegistry projectionRegistry; // M9：写后直失效（D3-C 主通道；可空）

    public SessionSurfaceStore(SessionRepository sessionRepository,
                               SessionSurfaceRepository surfaceRepository,
                               SessionEventBus eventBus,
                               Optional<SessionProjectionRegistry> projectionRegistry) {
        this.sessionRepository = sessionRepository;
        this.surfaceRepository = surfaceRepository;
        this.eventBus = eventBus;
        this.projectionRegistry = projectionRegistry.orElse(null);
    }

    /**
     * REPLACE_HEAD：遮蔽日志头 {@code [1..shadowedHeadCount]}，并以 {@code replacement} 作为该段的
     * 可见代表行（压缩用：摘要替代被压缩旧头，语义位置在头部）。压缩时调用。
     *
     * @return 写入指令的 gen；{@code shadowedHeadCount <= 0} 时为 no-op 返回 0
     */
    @Transactional
    public long replaceHead(SessionId sessionId, long shadowedHeadCount, String replacement, String reason) {
        if (shadowedHeadCount <= 0) {
            return 0L;
        }
        SurfaceInstruction instruction = SurfaceInstruction.replaceHead(
                nextGen(sessionId), shadowedHeadCount, replacement, meta(reason, false));
        return write(sessionId, instruction);
    }

    /**
     * REPLACE_RANGE：遮蔽任意 {@code [from..to]} 段（从事实 seq 起止，含端点）；
     * {@code replacement} 非空时以该行折叠代替该段（中间 TOOL 段折叠/中间回答折叠）。
     *
     * @return 写入指令的 gen
     */
    @Transactional
    public long replaceRange(SessionId sessionId, long from, long to, String replacement, String reason) {
        if (from < 1 || to < from) {
            throw new IllegalArgumentException("非法遮蔽区间: from=" + from + " to=" + to + "（需 1 <= from <= to）");
        }
        return write(sessionId, SurfaceInstruction.replaceRange(nextGen(sessionId), from, to, replacement, meta(reason, false)));
    }

    /**
     * restoreRange：回卷 —— 追加覆盖指令使 {@code [from..to]} 恢复可见（append-only，审计完整；
     * 遮蔽区间表按 gen 重叠取最新，见 SurfaceProjector）。恢复不删任何历史指令。
     *
     * @return 写入指令的 gen
     */
    @Transactional
    public long restoreRange(SessionId sessionId, long from, long to, String reason) {
        if (from < 1 || to < from) {
            throw new IllegalArgumentException("非法回卷区间: from=" + from + " to=" + to + "（需 1 <= from <= to）");
        }
        return write(sessionId, SurfaceInstruction.restoreRange(nextGen(sessionId), from, to, meta(reason, true)));
    }

    /** 会话全部表面指令（gen 升序；当前可见面 = 依序应用遮蔽区间，读 seam SurfaceProjector 消费）。 */
    @Transactional(readOnly = true)
    public List<SurfaceInstruction> listInstructions(SessionId sessionId) {
        return surfaceRepository.findBySessionIdOrderByGenAsc(sessionId.value()).stream()
                .map(SessionSurfaceEntity::toInstruction)
                .toList();
    }

    /** 当前可见面代数（= 已写表面指令数；append-only 连续无洞，并发 gate 保证）。 */
    @Transactional(readOnly = true)
    public long currentGeneration(SessionId sessionId) {
        return surfaceRepository.countBySessionId(sessionId.value());
    }

    /** 下一 gen：先碰会话行乐观锁（写权独占 gate），后 count（gate 后稳定，无并发错序）。 */
    private long nextGen(SessionId sessionId) {
        Instant now = Instant.now();
        var entity = sessionRepository.findById(sessionId.value())
                .orElseThrow(() -> new SessionService.SessionNotFoundException(sessionId));
        entity.setUpdatedAt(now);
        try {
            sessionRepository.saveAndFlush(entity);
        } catch (OptimisticLockingFailureException e) {
            throw new SessionService.SessionConcurrentModificationException(sessionId.value(), e);
        }
        return surfaceRepository.countBySessionId(sessionId.value()) + 1;
    }

    private long write(SessionId sessionId, SurfaceInstruction instruction) {
        surfaceRepository.saveAndFlush(SessionSurfaceEntity.of(
                "surface_" + UUID.randomUUID(), sessionId.value(), instruction.gen(), instruction.op(),
                instruction.rangeFrom(), instruction.rangeTo(), instruction.replacement(),
                instruction.metaJson(), Instant.now()));
        if (projectionRegistry != null) {
            projectionRegistry.invalidate(sessionId); // M9 直失效：写事务内清缓存，后续读必重载
        }
        String reason = extractReason(instruction.metaJson());
        eventBus.publish(sessionId, SessionEventType.SESSION_SURFACE_CHANGED, Map.of(
                "gen", instruction.gen(),
                "op", instruction.op().name(),
                "rangeFrom", instruction.rangeFrom(),
                "rangeTo", instruction.rangeTo() == null ? "" : String.valueOf(instruction.rangeTo()),
                "reason", reason == null ? "" : reason));
        log.info("[SurfaceOp] session={} gen={} op={} range=[{},{}] replacement={} reason={}",
                sessionId, instruction.gen(), instruction.op(), instruction.rangeFrom(), instruction.rangeTo(),
                instruction.replacement() == null ? "-" : instruction.replacement().length() + "ch", reason);
        return instruction.gen();
    }

    /** metaJson（JSON 文本，简单转义字段；reason 来自内部常量化调用方，无引号注入）。 */
    private static String meta(String reason, boolean restores) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        if (reason != null && !reason.isBlank()) {
            sb.append("\"reason\":\"").append(reason).append('"');
            first = false;
        }
        if (restores) {
            if (!first) {
                sb.append(',');
            }
            sb.append("\"restores\":true");
        }
        sb.append('}');
        return sb.toString();
    }

    private static String extractReason(String metaJson) {
        if (metaJson == null || !metaJson.contains("\"reason\"")) {
            return null;
        }
        int idx = metaJson.indexOf("\"reason\"");
        int colon = metaJson.indexOf(':', idx);
        int open = metaJson.indexOf('"', colon);
        int close = metaJson.indexOf('"', open + 1);
        return open >= 0 && close > open ? metaJson.substring(open + 1, close) : null;
    }
}
