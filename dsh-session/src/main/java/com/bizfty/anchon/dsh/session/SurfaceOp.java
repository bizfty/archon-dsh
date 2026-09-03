package com.bizfty.anchon.dsh.session;

/**
 * 表面操作类型（anchon_session_surface.op）— 模型可见面（surface）显式演进指令。
 * <p>
 * 对应上游 surfaceOp（generation/replace/append）语义（docs/design-surface-op.md §4.1）：
 * <ul>
 *   <li>{@link #REPLACE_HEAD}：遮蔽日志头 {@code [1..rangeTo]}，并以 {@code replacement} 为该段的
 *       可见代表行（对齐 CompactionBoundaryStore 单点 replace 头语义；压缩写此 op）。</li>
 *   <li>{@link #REPLACE_RANGE}：遮蔽任意 {@code [rangeFrom..rangeTo]} 段，{@code replacement} 非空时
 *       以该行代替该段（折叠中间 TOOL 结果 / 中间回答）。</li>
 *   <li>{@link #APPEND_VIEW}：仅附加可见性备注（如回卷记录），不改变遮蔽集合。</li>
 * </ul>
 * 指令 append-only 累积；"当前可见面"按 {@code (sessionId, gen)} 升序应用，
 * 遮蔽区间重叠时取 gen 大者（后操作覆盖先操作）。
 */
public enum SurfaceOp {
    REPLACE_HEAD,
    REPLACE_RANGE,
    APPEND_VIEW
}
