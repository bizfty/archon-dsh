package com.bizfty.anchon.dsh.session;

/**
 * 表面指令（surface instruction）— {@code anchon_session_surface} 一行的领域视图。
 *
 * @param gen        可见面代数（会话内单调，1..N；每次表面操作 +1）
 * @param op         表面操作类型
 * @param rangeFrom  作用的事实 seq 起始（含）；REPLACE_HEAD 语义等价 rangeFrom=1
 * @param rangeTo    事实 seq 结束（含）；null = 到当前末尾（REPLACE_HEAD 记录实际遮蔽头数，非 null）
 * @param replacement REPLACE 时的摘要/折叠文本（USER 角色代表行）；null = 纯遮蔽（隐藏不替换）
 * @param metaJson   可选元数据：{@code {"reason":"compaction|manual|tool-fold|restore",...}} 审计等
 */
public record SurfaceInstruction(
        long gen,
        SurfaceOp op,
        long rangeFrom,
        Long rangeTo,
        String replacement,
        String metaJson) {

    /** REPLACE_HEAD 构造器：遮蔽 {@code [1..shadowedHeadCount]}。 */
    public static SurfaceInstruction replaceHead(long gen, long shadowedHeadCount, String replacement, String metaJson) {
        return new SurfaceInstruction(gen, SurfaceOp.REPLACE_HEAD, 1L, shadowedHeadCount, replacement, metaJson);
    }

    /** REPLACE_RANGE 构造器：遮蔽任意 {@code [from..to]} 段（可选折叠代表行）。 */
    public static SurfaceInstruction replaceRange(long gen, long from, long to, String replacement, String metaJson) {
        return new SurfaceInstruction(gen, SurfaceOp.REPLACE_RANGE, from, to, replacement, metaJson);
    }

    /** restore 覆盖指令（append-only 回卷）：遮蔽区间表对 {@code [from..to]} 标记"恢复可见"。 */
    public static SurfaceInstruction restoreRange(long gen, long from, long to, String metaJson) {
        return new SurfaceInstruction(gen, SurfaceOp.APPEND_VIEW, from, to, null,
                metaJson == null ? "{\"restores\":true}" : metaJson);
    }
}
