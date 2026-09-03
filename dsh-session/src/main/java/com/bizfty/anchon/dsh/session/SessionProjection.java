package com.bizfty.anchon.dsh.session;

import java.util.List;

/**
 * 会话级派生状态快照（M9 投影 registry 化，docs/design-projection-cache.md §4.1）—
 * 遮蔽区间表 + 可见面代数的不可变物化。
 * <p>
 * 只缓存**不依赖每 turn 参数**的部分：遮蔽区间表是指令序列（append-only）的确定性函数，
 * 两次 surface 写之间逐位相同；fact/投影行 append 不影响既有遮蔽判定（区间端点写定时固定）。
 * 可见消息列表 / 窗口 / 配对过滤依赖每 turn 的 history 与窗口参数，**不在此快照内**。
 * <p>
 * 构造仅限同包（{@link SessionProjectionRegistry} 装载时创建）；会话无表面指令时不建快照
 * （读 seam 走 legacy fast-path，boundary 实时值由调用方持有）。
 *
 * @param surfaceGen 可见面代数 = 已应用表面指令数（DB count 口径；快照装载时的指令数）
 * @param segments   遮蔽区间表（有序、做差完毕、不可变 —— 调用方须传不可变副本；恒非空：无指令不建快照）
 */
public final class SessionProjection {

    private final long surfaceGen;
    private final List<SurfaceProjector.Segment> segments;

    SessionProjection(long surfaceGen, List<SurfaceProjector.Segment> segments) {
        if (segments == null || segments.isEmpty()) {
            throw new IllegalArgumentException("无表面指令不建派生快照（调用方应走 legacy fast-path）");
        }
        this.surfaceGen = surfaceGen;
        this.segments = segments;
    }

    /** 可见面代数（长会话 surface 写次数；随压缩/折叠/恢复单调增长）。 */
    public long surfaceGen() {
        return surfaceGen;
    }

    /** 遮蔽区间表（同包消费：SurfaceProjector applySegments；对外不暴露 Segment 类型）。 */
    List<SurfaceProjector.Segment> segments() {
        return segments;
    }

    @Override
    public String toString() {
        return "SessionProjection{surfaceGen=" + surfaceGen + ", segments=" + segments.size() + "}";
    }
}
