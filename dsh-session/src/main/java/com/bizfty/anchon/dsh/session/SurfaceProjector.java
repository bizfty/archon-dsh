package com.bizfty.anchon.dsh.session;

import com.bizfty.anchon.dsh.core.model.MessageRole;
import com.bizfty.anchon.dsh.core.model.SessionMessage;
import com.bizfty.anchon.dsh.util.JsonUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 表面投影执行 seam（M8 surfaceOp，docs/design-surface-op.md §4.2/§4.3；M9 registry 化，
 * docs/design-projection-cache.md §4.1）— "模型可见面"的读侧解析。
 * <p>
 * 输入：会话全量消息（fact/投影缓存按 seq 升序）+ 表面指令（gen 升序）+ legacy 遮蔽边界 +
 * 尾部窗口 + 排除尾数（当前 turn 刚写入的 USER）。输出：模型可见消息序列（含 replacement 视图行，
 * 不含被排除尾部）。
 * <p>
 * 解析规则（§4.2）：
 * <ol>
 *   <li>遮蔽区间表：REPLACE_HEAD=遮蔽 [1..to]、REPLACE_RANGE=遮蔽 [from..to]（重叠取最新 gen，
 *       即新区间内的旧遮蔽被覆盖）；APPEND_VIEW + {@code meta.restores=true}（restoreRange 写出）
 *       对遮蔽区间做差 = 恢复可见。表面指令存在时 {@code legacyBoundary} 忽略（D3-A）；指令为空时
 *       {@code legacyBoundary>0} 作为隐式 REPLACE_HEAD —— 与现状 CompactionBoundaryStore 语义逐条等价。</li>
 *   <li>遍历消息：seq 落在遮蔽区间 → 隐藏；区间带 replacement → 在其起点插入派生 USER 视图行
 *       （摘要/折叠文本显示于被替换段的原位置，修正历史"物理末尾 vs 可见头部"错位）。</li>
 *   <li>D9-A 去重：replacement 已输出时，跳过 role=USER 且 content 与 replacement 相同的物理行
 *       （压缩仍 append 摘要物理行到日志末尾，避免同一摘要输出两次）。</li>
 *   <li>尾部窗口：可见序列超 {@code maxHistory} 保留尾部（等效现状 from=max(boundary,size-maxHistory)）。</li>
 *   <li>配对过滤：assistant(tool_calls) 须被窗口内 TOOL 全覆盖，孤立 TOOL 跳过（现状规则原样迁移，
 *       防 OpenAI 400）。</li>
 * </ol>
 * 遮蔽区间表由 {@link SessionProjectionRegistry} 物化为会话级派生状态（M9：表只依赖 append-only
 * 指令序列，两次 surface 写之间逐位相同 → 命中即省 buildSegments O(K²) 与指令全量 DB 读）；
 * 本组件保持纯逻辑：无论传入原始指令（直算，契约锁）还是派生快照（缓存命中），
 * applySegments/窗口/配对过滤路径完全一致。不触碰库（指令/消息由调用方读取后传入）。
 */
@Component
public class SurfaceProjector {

    /** 遮蔽段：可见范围内的一段（from..to 含端点）；replacement 非空 = 该段的可见代表行。 */
    record Segment(long from, long to, String replacement) {
    }

    private final JsonUtils jsonUtils;

    public SurfaceProjector(JsonUtils jsonUtils) {
        this.jsonUtils = jsonUtils;
    }

    /**
     * 计算模型可见消息序列（原始指令直算路径 —— 契约锁基准；M8 seam 与 M9 双跑等价对照）。
     *
     * @param history        会话全量消息（seq 升序；含刚写入的当前 turn USER 于末尾，由 excludeTrailing 排除）
     * @param instructions   表面指令（gen 升序；可为空列表）
     * @param legacyBoundary CompactionBoundaryStore 遮蔽边界（无 surface 指令时作为隐式 REPLACE_HEAD）
     * @param maxHistory     尾部窗口上限（现状 properties.maxHistoryMessages）
     * @param excludeTrailing 从输出尾部排除的消息数（当前 turn USER=1；0 = 不排除）
     * @return 模型可见消息序列（顺序保持；replacement 视图行已插入遮蔽段起点）
     */
    public List<SessionMessage> projectVisible(List<SessionMessage> history,
                                               List<SurfaceInstruction> instructions,
                                               long legacyBoundary,
                                               int maxHistory,
                                               int excludeTrailing) {
        List<SessionMessage> safeHistory = history == null ? List.of() : history;
        List<SurfaceInstruction> safeOps = instructions == null ? List.of() : instructions;

        // D3-A fast-path：无表面指令 → legacy 语义与现状逐条同构（boundary 起播 + 尾部窗口 +
        // 排除末尾当前 USER）。from 基于含尾 USER 的原始长度（与 AgentLoop 现状公式一致）。
        if (safeOps.isEmpty()) {
            int size = safeHistory.size();
            int from = Math.max((int) legacyBoundary, Math.max(0, size - Math.max(0, maxHistory)));
            int toExclusive = Math.max(from, size - Math.max(0, excludeTrailing));
            return pairingFilter(safeHistory.subList(Math.min(from, size), Math.min(toExclusive, size)));
        }

        // 表面指令路径：遮蔽区间表 → 遮蔽 + replacement 视图行 + D9-A 去重 → 排尾 → 窗口 → 配对过滤。
        return projectFromSegments(safeHistory, buildSegments(safeOps, legacyBoundary),
                maxHistory, excludeTrailing);
    }

    /**
     * 计算模型可见消息序列（M9 派生快照路径 —— registry 命中时跳过 buildSegments）。
     * 快照的遮蔽区间表与直算 buildSegments 结果逐位一致（契约锁双跑），后续处理路径完全相同。
     *
     * @param history         会话全量消息（seq 升序）
     * @param projection      会话级派生快照（遮蔽区间表已物化；非空，含 replacement 代表行）
     * @param maxHistory      尾部窗口上限
     * @param excludeTrailing 从输出尾部排除的消息数
     * @return 模型可见消息序列
     */
    public List<SessionMessage> projectVisible(List<SessionMessage> history,
                                               SessionProjection projection,
                                               int maxHistory,
                                               int excludeTrailing) {
        List<SessionMessage> safeHistory = history == null ? List.of() : history;
        if (projection == null || projection.segments().isEmpty()) {
            // 防御：快照为空退化为纯窗口（无遮蔽）—— 理论上 registry 不为无指令会话建快照，
            // 此处保底使调用方无需分支。
            int size = safeHistory.size();
            int from = Math.max(0, size - Math.max(0, maxHistory));
            int toExclusive = Math.max(from, size - Math.max(0, excludeTrailing));
            return pairingFilter(safeHistory.subList(Math.min(from, size), Math.min(toExclusive, size)));
        }
        return projectFromSegments(safeHistory, projection.segments(), maxHistory, excludeTrailing);
    }

    /** 遮蔽区间表 + 后续统一处理：applySegments → 排尾 → 窗口 → 配对过滤。 */
    private List<SessionMessage> projectFromSegments(List<SessionMessage> history,
                                                     List<Segment> segments,
                                                     int maxHistory,
                                                     int excludeTrailing) {
        List<SessionMessage> visible = applySegments(history, segments);

        int end = visible.size() - Math.max(0, excludeTrailing);
        if (end < 0) {
            end = 0;
        }
        List<SessionMessage> windowed = visible.subList(0, end);
        if (windowed.size() > Math.max(0, maxHistory)) {
            windowed = windowed.subList(windowed.size() - Math.max(0, maxHistory), windowed.size());
        }
        return pairingFilter(windowed);
    }

    /** 遮蔽区间表构建：指令升序应用（新 REPLACE 覆盖旧遮蔽段内 replacement；restore 做差）。 */
    List<Segment> buildSegments(List<SurfaceInstruction> ops, long legacyBoundary) {
        List<SurfaceInstruction> effective = ops.isEmpty()
                ? (legacyBoundary > 0
                        ? List.of(SurfaceInstruction.replaceHead(0, legacyBoundary, null, "legacy-boundary"))
                        : List.of())
                : ops;
        List<Segment> segments = new ArrayList<>();
        for (SurfaceInstruction op : effective) {
            long from = op.rangeFrom();
            long to = op.rangeTo() == null ? Long.MAX_VALUE : op.rangeTo();
            if (op.op() == SurfaceOp.REPLACE_HEAD || op.op() == SurfaceOp.REPLACE_RANGE) {
                segments = cover(segments, from, to, op.replacement());
            } else if (op.op() == SurfaceOp.APPEND_VIEW && isRestore(op)) {
                segments = uncover(segments, from, to);
            }
            // 其它 APPEND_VIEW（非 restore）仅备注，不改遮蔽
        }
        return segments;
    }

    private static boolean isRestore(SurfaceInstruction op) {
        return op.metaJson() != null && op.metaJson().contains("\"restores\"");
    }

    /** 覆盖 [from..to]：与既有段相交部分被新区段覆盖（含 replacement），不相交部分保留。 */
    private static List<Segment> cover(List<Segment> in, long from, long to, String replacement) {
        List<Segment> out = new ArrayList<>();
        for (Segment s : in) {
            if (s.to < from || s.from > to) {
                out.add(s); // 不相交，保留
                continue;
            }
            if (s.from < from) {
                out.add(new Segment(s.from, from - 1, s.replacement)); // 左残余（保留原 replacement）
            }
            if (s.to > to) {
                out.add(new Segment(to + 1, s.to, s.replacement)); // 右残余
            }
            // 相交部分被新区段覆盖：replacement 丢弃（由新区段表达）
        }
        out.add(new Segment(from, to, replacement));
        out.sort((a, b) -> Long.compare(a.from, b.from));
        return out;
    }

    /** 恢复 [from..to]（restoreRange）：遮蔽区间做差，残余保留原 replacement。 */
    private static List<Segment> uncover(List<Segment> in, long from, long to) {
        List<Segment> out = new ArrayList<>();
        for (Segment s : in) {
            if (s.to < from || s.from > to) {
                out.add(s);
                continue;
            }
            if (s.from < from) {
                out.add(new Segment(s.from, from - 1, s.replacement));
            }
            if (s.to > to) {
                out.add(new Segment(to + 1, s.to, s.replacement));
            }
        }
        out.sort((a, b) -> Long.compare(a.from, b.from));
        return out;
    }

    /** 遍历消息应用遮蔽：隐藏遮蔽段、插 replacement 视图行、D9-A 去重物理摘要行。 */
    private List<SessionMessage> applySegments(List<SessionMessage> history, List<Segment> segments) {
        List<SessionMessage> visible = new ArrayList<>();
        com.bizfty.anchon.dsh.core.model.SessionId sessionId = history.isEmpty() ? null : history.get(0).sessionId();
        Map<String, Boolean> emitted = new HashMap<>(); // replacement 文本 → 已输出
        int s = 0;
        for (SessionMessage msg : history) {
            long seq = msg.seq();
            while (s < segments.size() && seq > segments.get(s).to()) {
                s++;
            }
            boolean hidden = false;
            if (s < segments.size() && seq >= segments.get(s).from() && seq <= segments.get(s).to()) {
                Segment seg = segments.get(s);
                if (seg.replacement() != null && !Boolean.TRUE.equals(emitted.get(seg.replacement()))) {
                    visible.add(viewRow(sessionId, seg));
                    emitted.put(seg.replacement(), Boolean.TRUE);
                }
                hidden = true;
            }
            if (!hidden) {
                if (msg.role() == MessageRole.USER && msg.content() != null && emitted.containsKey(msg.content())) {
                    continue; // D9-A：该物理行已被 replacement 视图行代表（压缩摘要物理行），去重
                }
                visible.add(msg);
            }
        }
        return visible;
    }

    /** replacement 派生视图行（非 fact 行、无真实 seq；渲染层按 USER 文本处理）。 */
    private static SessionMessage viewRow(com.bizfty.anchon.dsh.core.model.SessionId sessionId, Segment seg) {
        return new SessionMessage("surface_rep_" + seg.from() + "_" + seg.to(),
                sessionId, MessageRole.USER, seg.replacement(), null, null, null,
                Math.max(0, seg.from() - 1), java.time.Instant.now(), false);
    }

    /** 配对过滤（现状 AgentLoop 规则原样迁移）：assistant(tool_calls) 与 TOOL 全对；孤立 TOOL 跳过；
     *  不完整 assistant 剥离 tool_calls 降级为纯文本（避免模型收到 tool_calls 无对应 tool 消息的 400）。 */
    List<SessionMessage> pairingFilter(List<SessionMessage> in) {
        Set<String> windowToolIds = new HashSet<>();
        for (SessionMessage m : in) {
            if (m.role() == MessageRole.TOOL && m.toolCallId() != null) {
                windowToolIds.add(m.toolCallId());
            }
        }
        Set<String> completeAssistantToolIds = new HashSet<>();
        for (SessionMessage m : in) {
            if (m.role() == MessageRole.ASSISTANT && m.toolCallsJson() != null) {
                List<String> ids = toolCallIds(m.toolCallsJson());
                if (!ids.isEmpty() && windowToolIds.containsAll(ids)) {
                    completeAssistantToolIds.addAll(ids);
                }
            }
        }
        List<SessionMessage> out = new ArrayList<>();
        for (SessionMessage m : in) {
            if (m.role() == MessageRole.TOOL && m.toolCallId() != null
                    && !completeAssistantToolIds.contains(m.toolCallId())) {
                continue; // 孤立 TOOL：其 assistant(tool_calls) 不在窗口内（或已被剥离），跳过
            }
            if (m.role() == MessageRole.ASSISTANT && m.toolCallsJson() != null && !m.toolCallsJson().isBlank()) {
                List<String> ids = toolCallIds(m.toolCallsJson());
                if (!ids.isEmpty() && !windowToolIds.containsAll(ids)) {
                    // tool_calls 未被窗口内 TOOL 完整覆盖：只发文本（现状第三循环语义）
                    out.add(new SessionMessage(m.id(), m.sessionId(), MessageRole.ASSISTANT,
                            m.content() == null ? "" : m.content(), null, null, null,
                            m.seq(), m.createdAt(), m.pruned()));
                    continue;
                }
            }
            out.add(m);
        }
        return out;
    }

    /** 从 assistant 的 toolCallsJson（JSON 数组 [{id,type,name,arguments}]）提取全部 tool call id。 */
    List<String> toolCallIds(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<Map<String, Object>> list = jsonUtils.toList(json);
            return list.stream()
                    .map(m -> m.get("id") == null ? null : String.valueOf(m.get("id")))
                    .filter(java.util.Objects::nonNull)
                    .toList();
        } catch (RuntimeException e) {
            return List.of();
        }
    }
}
