package com.bizfty.anchon.dsh.session;

import com.bizfty.anchon.dsh.core.model.MessageRole;
import com.bizfty.anchon.dsh.core.model.SessionId;
import com.bizfty.anchon.dsh.core.model.SessionMessage;
import com.bizfty.anchon.dsh.util.JsonUtils;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SurfaceProjector（M8 读侧 seam）单元测试：
 * <ul>
 *   <li>红线2 等价性：空表面指令 + legacyBoundary 时，seam 输出与现状（boundary 起播 + 尾部窗口 +
 *       配对过滤 + 排除当前 USER）逐条一致（reference 实现对照）；</li>
 *   <li>REPLACE_HEAD replacement：摘要视图行出现在被遮蔽段原起点（修正 seq 末尾错位）；D9-A 物理
 *       摘要行去重；</li>
 *   <li>纯遮蔽无 replacement。</li>
 * </ul>
 * REPLACE_RANGE 折叠中段与 restore 回卷语义在 m8-4 专项用例覆盖（本文件含基础 sanity）。
 */
class SurfaceProjectorTest {

    private final JsonUtils jsonUtils = new JsonUtils();
    private final SurfaceProjector projector = new SurfaceProjector(jsonUtils);
    private final SessionId sid = SessionId.of("sess_surface_proj_test");

    // ---- history 构造 ----

    private SessionMessage m(long seq, MessageRole role, String content, String toolCallId, String toolCallsJson) {
        return new SessionMessage("msg_" + seq, sid, role, content, toolCallId, "tool", toolCallsJson, seq,
                Instant.now(), false);
    }

    private SessionMessage user(long seq, String content) {
        return m(seq, MessageRole.USER, content, null, null);
    }

    private SessionMessage assistant(long seq, String content, String toolCallsJson) {
        return m(seq, MessageRole.ASSISTANT, content, null, toolCallsJson);
    }

    private SessionMessage tool(long seq, String toolCallId, String content) {
        return m(seq, MessageRole.TOOL, content, toolCallId, null);
    }

    /** 构造 n 条简单会话（USER/ASSISTANT 交替；无 tool_calls）。seq 1..n。 */
    private List<SessionMessage> plainHistory(int n) {
        List<SessionMessage> list = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            list.add(i % 2 == 1 ? user(i, "用户消息 " + i) : assistant(i, "助手回复 " + i, null));
        }
        return list;
    }

    /** 构造带工具调用的会话：偶发 assistant(tool_calls cX) + tool(cX)。 */
    private List<SessionMessage> toolHistory() {
        List<SessionMessage> list = new ArrayList<>();
        list.add(user(1, "查一下"));
        list.add(assistant(2, "好的", "[{\"id\":\"c1\",\"type\":\"function\",\"name\":\"search\",\"arguments\":\"{}\"}]"));
        list.add(tool(3, "c1", "{\"ok\":true}"));
        list.add(assistant(4, "找到 1 条", null));
        list.add(user(5, "继续"));
        list.add(assistant(6, "好的", "[{\"id\":\"c2\",\"type\":\"function\",\"name\":\"search\",\"arguments\":\"{}\"}]"));
        list.add(tool(7, "c2", "{\"ok\":false}"));
        list.add(assistant(8, "没找到", null));
        list.add(user(9, "当前 USER")); // 末尾 = 当前 turn USER（excludeTrailing=1 排除）
        return list;
    }

    // ---- 红线2：空 surface + legacyBoundary 与现状逐条等价 ----

    @Test
    void emptySurfaceWithLegacyBoundaryEqualsLegacyWindow() {
        for (int n : new int[]{3, 12, 30}) {
            List<SessionMessage> history = toolHistory().subList(0, Math.min(n, toolHistory().size()));
            if (history.size() < 3) {
                continue;
            }
            for (int boundary : new int[]{0, 2, 5}) {
                for (int maxHistory : new int[]{3, 8, 100}) {
                    List<SessionMessage> legacy = legacy(history, boundary, maxHistory);
                    List<SessionMessage> seam = projector.projectVisible(history, List.of(), boundary, maxHistory, 1);
                    assertEquals(legacy.size(), seam.size(), "等价性：boundary=" + boundary + " maxHistory=" + maxHistory);
                    for (int i = 0; i < legacy.size(); i++) {
                        assertEquals(legacy.get(i).id(), seam.get(i).id(), "第 " + i + " 条应逐条一致");
                        assertEquals(legacy.get(i).role(), seam.get(i).role());
                        assertEquals(legacy.get(i).content(), seam.get(i).content());
                    }
                }
            }
        }
    }

    /** 现状 reference：boundary 起播 + 尾部窗口 + 配对过滤 + 排除末尾当前 USER（toExclusive=size-1）。 */
    private List<SessionMessage> legacy(List<SessionMessage> history, int boundary, int maxHistory) {
        int from = Math.max(boundary, Math.max(0, history.size() - maxHistory));
        int toExclusive = history.size() - 1;
        List<SessionMessage> windowed = new ArrayList<>();
        for (int i = from; i < toExclusive; i++) {
            windowed.add(history.get(i));
        }
        return projector.pairingFilter(windowed);
    }

    @Test
    void legacyWindowDropsOrphanToolAndKeepsPair() {
        // 现状配对过滤 sanity：完整对保留；若 TOOL 孤立（其 assistant(tool_calls) 在窗口外）则被跳。
        List<SessionMessage> history = toolHistory();
        // maxHistory=100 全可见 → 完整对（c1/c2）都保留
        List<SessionMessage> seam = projector.projectVisible(history, List.of(), 0, 100, 1);
        assertEquals(8, seam.size()); // 排除尾 USER(9)：8 条全可见，配对完整
        // 窗口 from=max(6, 9-4=5)=6，toExclusive=8 → 窗口 [seq7 TOOL(c2), seq8 assistant]；
        // seq7 的 assistant(c2)（seq6）在窗口外 → TOOL c2 孤立被过滤 → 仅 seq8 输出。
        List<SessionMessage> narrow = projector.projectVisible(history, List.of(), 6, 4, 1);
        assertEquals(1, narrow.size());
        assertEquals("msg_8", narrow.get(0).id());
    }

    // ---- REPLACE_HEAD + replacement（摘要视图行置头 + D9-A 去重）----

    @Test
    void replaceHeadPutsSummaryAtHeadAndDeduplicatesPhysicalSummary() {
        // 历史：旧头 1..6 被压缩；物理摘要行 append 在末尾（seq 7）；当前 USER seq 8。
        List<SessionMessage> history = new ArrayList<>(plainHistory(6));
        history.add(user(7, "（历史压缩摘要）\n早前对话摘要..."));
        history.add(user(8, "当前问题"));
        List<SurfaceInstruction> ops = List.of(
                SurfaceInstruction.replaceHead(1, 6, "（历史压缩摘要）\n早前对话摘要...", "compaction"));

        List<SessionMessage> visible = projector.projectVisible(history, ops, 0, 100, 1);

        // 期望：摘要视图行在头部（替代 seq1..6），物理摘要行(seq7)去重，当前 USER(seq8) 排除。
        assertEquals(1, visible.size());
        assertEquals(MessageRole.USER, visible.get(0).role());
        assertEquals("（历史压缩摘要）\n早前对话摘要...", visible.get(0).content());
        assertTrue(visible.get(0).id().startsWith("surface_rep_"), "视图行 id 前缀 surface_rep_");
    }

    @Test
    void replaceHeadWithoutReplacementPureShadows() {
        List<SessionMessage> history = plainHistory(12);
        history.add(user(13, "当前问题")); // seq13 尾 USER 排除
        List<SurfaceInstruction> ops = List.of(SurfaceInstruction.replaceHead(1, 8, null, "manual"));

        List<SessionMessage> visible = projector.projectVisible(history, ops, 0, 100, 1);
        // seq1..8 纯遮蔽；可见 seq9..12（4 条）；seq13 排除
        assertEquals(4, visible.size());
        assertEquals("msg_9", visible.get(0).id());
        assertEquals("msg_12", visible.get(visible.size() - 1).id());
    }

    @Test
    void surfaceInstructionsTakePrecedenceOverLegacyBoundary() {
        // D3-A：surface 指令存在时 legacyBoundary 忽略（不叠加遮蔽）。
        List<SessionMessage> history = plainHistory(12);
        history.add(user(13, "当前问题"));
        List<SurfaceInstruction> ops = List.of(SurfaceInstruction.replaceHead(1, 4, null, "manual"));
        // legacyBoundary=10 若叠加会把 5..10 也遮蔽 → 只有 4 条可见；忽略 → 8 条可见（seq5..12）
        List<SessionMessage> visible = projector.projectVisible(history, ops, 10, 100, 1);
        assertEquals(8, visible.size());
        assertEquals("msg_5", visible.get(0).id());
    }
}
