package com.bizfty.anchon.dsh.session;

import com.bizfty.anchon.dsh.core.event.SessionEventBus;
import com.bizfty.anchon.dsh.core.event.SessionEventType;
import com.bizfty.anchon.dsh.core.model.SessionId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 会话级投影派生状态 registry（M9，docs/design-projection-cache.md §4.2/§4.4）— 遮蔽区间表进程内物化。
 * <p>
 * 动机：遮蔽区间表只依赖 append-only 表面指令序列；两次 surface 写（压缩/折叠/恢复）之间的所有
 * turn 读到相同指令集，却每次从 DB 全量重读 + O(K²) 重建。registry 把该派生状态按会话缓存，
 * 读路径降为一次 Map 命中。
 * <p>
 * 一致性（D4 裁决 A0 + A1 开关位）：
 * <ul>
 *   <li>A0（默认，零 DB 附加）：仅本地失效 —— 写后由 {@link SessionSurfaceStore} 直失效
 *       （D3-C 主通道）与 {@code SESSION_SURFACE_CHANGED} 事件监听（兜底）双通道清除；单实例强一致。</li>
 *   <li>A1（开关 {@code dsh.session.projection-cache.verify-gen=true}）：snapshot 时先取 DB 代数
 *       （调用方传入 {@code genLoader}，默认不触发）与缓存代数比对，落后即重建 —— 多实例水平扩展正确，
 *       读路径 +1 count。</li>
 * </ul>
 * 容量（D5）：maxEntries 默认 4096（{@code dsh.session.projection-cache.max-entries}），超限逐出
 * lastAccess 最旧一半（LRU-ish，无三方依赖；逐出后读回 DB 重建，正确性无损）。
 * <p>
 * 归属 dsh-session：与 {@link SurfaceProjector} 同包，{@code Segment}/{@code buildSegments} 包可见
 * 直接复用；依赖方向 AgentLoop → Registry → Store（loader/genLoader 由调用方以 Supplier 传入，
 * registry 不静态依赖 Store，避免回引 dsh-agent）。
 */
@Component
public class SessionProjectionRegistry {

    private static final Logger log = LoggerFactory.getLogger(SessionProjectionRegistry.class);

    private final SurfaceProjector projector;
    private final SessionEventBus eventBus;
    private final int maxEntries;
    private final boolean verifyGen;

    private final ConcurrentHashMap<SessionId, Entry> cache = new ConcurrentHashMap<>();

    private static final class Entry {
        final long surfaceGen;
        final List<SurfaceProjector.Segment> segments;
        volatile long lastAccessNanos;

        Entry(long surfaceGen, List<SurfaceProjector.Segment> segments) {
            this.surfaceGen = surfaceGen;
            this.segments = List.copyOf(segments); // 建时固化不可变；命中路径零拷贝
            this.lastAccessNanos = System.nanoTime();
        }
    }

    public SessionProjectionRegistry(SurfaceProjector projector,
                                     SessionEventBus eventBus,
                                     @Value("${dsh.session.projection-cache.max-entries:4096}") int maxEntries,
                                     @Value("${dsh.session.projection-cache.verify-gen:false}") boolean verifyGen) {
        this.projector = projector;
        this.eventBus = eventBus;
        this.maxEntries = Math.max(1, maxEntries);
        this.verifyGen = verifyGen;
        // D3-C 事件兜底（冗余失效幂等无害）：防未来绕过 SessionSurfaceStore 的 surface 写者。
        this.eventBus.addListener(event -> {
            if (event.type() == SessionEventType.SESSION_SURFACE_CHANGED) {
                invalidate(event.sessionId());
            }
        }, 100);
    }

    /**
     * 取该会话的派生快照（遮蔽区间表已物化）；无表面指令返回 {@link Optional#empty()}（不缓存 ——
     * legacy fast-path 由调用方以实时 boundary 走原 projectVisible）。
     * <p>
     * 原子装载：并发同 miss 由 CHM.compute 合并为一次 loader 调用 + buildSegments；命中刷 lastAccess。
     *
     * @param sessionId 会话
     * @param loader    表面指令装载（默认 {@code sessionSurfaceStore::listInstructions}；DB K 行全量）
     * @param genLoader 当前 DB 代数（仅 verifyGen=true 时被调用；默认关闭零 DB 开销）
     */
    public Optional<SessionProjection> snapshot(SessionId sessionId,
                                                Supplier<List<SurfaceInstruction>> loader,
                                                Supplier<Long> genLoader) {
        if (sessionId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(cache.compute(sessionId, (key, existing) -> {
            if (existing != null) {
                if (!verifyGen || existing.surfaceGen == genLoader.get()) {
                    existing.lastAccessNanos = System.nanoTime();
                    return existing;
                }
                // A1：DB 代数领先（其它实例已写 surface）→ 丢弃重载
                log.debug("[ProjectionRegistry] gen 落后 {}/{} 重建 session={}", existing.surfaceGen,
                        genLoader.get(), sessionId);
            }
            return load(sessionId, loader);
        })).map(e -> new SessionProjection(e.surfaceGen, e.segments));
    }

    /** 装载：指令非空 → buildSegments（指令非空时与 legacyBoundary 无关，传 0）；空 → 不缓存。 */
    private Entry load(SessionId sessionId, Supplier<List<SurfaceInstruction>> loader) {
        List<SurfaceInstruction> ops = loader.get();
        if (ops == null || ops.isEmpty()) {
            return null; // 无 surface 指令：registry 不建快照（fast-path 语义）
        }
        long gen = ops.get(ops.size() - 1).gen(); // append-only 连续无洞：尾指令 gen = 指令数
        if (cache.size() >= maxEntries) {
            evictOldestHalf();
        }
        return new Entry(gen, projector.buildSegments(ops, 0L));
    }

    /** 写失效（D3-C 主通道：SessionSurfaceStore 写后直调；事件兜底冗余）。 */
    public void invalidate(SessionId sessionId) {
        if (sessionId != null) {
            cache.remove(sessionId);
        }
    }

    /** 当前缓存会话数（测试/观察）。 */
    public int size() {
        return cache.size();
    }

    /** 超限逐出：按 lastAccess 升序移除最旧一半（LRU-ish）。 */
    private void evictOldestHalf() {
        List<Map.Entry<SessionId, Entry>> ordered = new ArrayList<>(cache.entrySet());
        ordered.sort(Comparator.comparingLong(e -> e.getValue().lastAccessNanos));
        int victims = Math.max(1, ordered.size() / 2);
        for (int i = 0; i < victims && i < ordered.size(); i++) {
            cache.remove(ordered.get(i).getKey(), ordered.get(i).getValue());
        }
        log.debug("[ProjectionRegistry] 超限逐出 {} entries（maxEntries={}）", victims, maxEntries);
    }
}
