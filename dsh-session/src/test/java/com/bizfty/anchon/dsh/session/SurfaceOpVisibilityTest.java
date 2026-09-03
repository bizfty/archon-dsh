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
 * M8 全能力投影层专项（docs/design-surface-op.md §4.2）：
 * REPLACE_RANGE 折叠任意段（含视图行位置）、restoreRange 回卷恢复、部分回卷（遮蔽挖洞）、
 * 重叠覆盖取最新 gen（replacement 覆盖）。窗口关闭（maxHistory 大、excludeTrailing=0）以便断言全貌。
 */
class SurfaceOpVisibilityTest {

    private final JsonUtils jsonUtils = new JsonUtils();
    private final SurfaceProjector projector = new SurfaceProjector(jsonUtils);
    private final SessionId sid = SessionId.of("sess_visibility_test");

    private SessionMessage m(long seq, MessageRole role, String content) {
        return new SessionMessage("msg_" + seq, sid, role, content, null, null, null, seq,
                Instant.now(), false);
    }

    private List<SessionMessage> history8() {
        List<SessionMessage> list = new ArrayList<>();
        list.add(m(1, MessageRole.USER, "q1"));
        list.add(m(2, MessageRole.ASSISTANT, "查一下"));
        list.add(m(3, MessageRole.TOOL, "{\"ok\":true}"));
        list.add(m(4, MessageRole.ASSISTANT, "回答1"));
        list.add(m(5, MessageRole.USER, "q2"));
        list.add(m(6, MessageRole.ASSISTANT, "再查"));
        list.add(m(7, MessageRole.TOOL, "{\"ok\":false}"));
        list.add(m(8, MessageRole.ASSISTANT, "回答2"));
        return list;
    }

    private List<String> visibleIds(List<SessionMessage> visible) {
        return visible.stream().map(SessionMessage::id).toList();
    }

    @Test
    void replaceRangeFoldsMiddleSegmentWithViewRowAtOriginalPosition() {
        List<SessionMessage> history = history8();
        List<SurfaceInstruction> ops = List.of(
                SurfaceInstruction.replaceRange(1, 3, 6, "[中间工具调用已折叠]", "tool-fold"));

        List<SessionMessage> visible = projector.projectVisible(history, ops, 0, 100, 0);

        // 期望：[msg_1, msg_2, 折叠视图行(在段起点), msg_7, msg_8]
        assertEquals(5, visible.size());
        assertEquals("msg_1", visible.get(0).id());
        assertEquals("msg_2", visible.get(1).id());
        assertTrue(visible.get(2).id().startsWith("surface_rep_"), "折叠视图行应插入遮蔽段起点");
        assertEquals("[中间工具调用已折叠]", visible.get(2).content());
        assertEquals(MessageRole.USER, visible.get(2).role());
        assertEquals("msg_7", visible.get(3).id());
        assertEquals("msg_8", visible.get(4).id());
    }

    @Test
    void restoreRangeBringsSegmentBack() {
        List<SessionMessage> history = history8();
        // 先折叠 [3..6]，再 restore [3..6] → 全部恢复可见
        List<SurfaceInstruction> ops = List.of(
                SurfaceInstruction.replaceRange(1, 3, 6, "[折叠]", "tool-fold"),
                SurfaceInstruction.restoreRange(2, 3, 6, null));

        List<SessionMessage> visible = projector.projectVisible(history, ops, 0, 100, 0);
        assertEquals(8, visible.size());
        assertEquals("msg_1", visible.get(0).id());
        assertEquals("msg_8", visible.get(7).id());
    }

    @Test
    void partialRestoreCutsHoleInShadow() {
        List<SessionMessage> history = history8();
        // 遮蔽 [2..7]，restore [4..5] → 残留遮蔽 [2..3]∪[6..7]，4/5 恢复
        List<SurfaceInstruction> ops = List.of(
                SurfaceInstruction.replaceRange(1, 2, 7, null, "tool-fold"),
                SurfaceInstruction.restoreRange(2, 4, 5, null));

        List<SessionMessage> visible = projector.projectVisible(history, ops, 0, 100, 0);
        // 期望：[1, 4, 5, 8]（2,3 遮蔽；6,7 遮蔽；无 replacement → 不插视图行）
        List<String> ids = visibleIds(visible);
        assertEquals(List.of("msg_1", "msg_4", "msg_5", "msg_8"), ids);
    }

    @Test
    void newerReplaceOverridesOlderReplacementWithinOverlap() {
        List<SessionMessage> history = history8();
        // REPLACE_HEAD 遮蔽 [1..5] 摘要A，随后 REPLACE_HEAD 遮蔽 [1..8] 摘要B → 最新遮蔽覆盖旧 replacement
        List<SurfaceInstruction> ops = List.of(
                SurfaceInstruction.replaceHead(1, 5, "摘要A", "compaction"),
                SurfaceInstruction.replaceHead(2, 8, "摘要B", "compaction"));

        List<SessionMessage> visible = projector.projectVisible(history, ops, 0, 100, 0);
        assertEquals(1, visible.size());
        assertEquals("摘要B", visible.get(0).content());
        assertTrue(visible.get(0).id().startsWith("surface_rep_"));
    }

    @Test
    void restoreOnlyRemovesShadowKeepsOtherReplacements() {
        List<SessionMessage> history = history8();
        // 遮蔽 [1..8] 摘要；restore [3..6] → 残留遮蔽 [1..2]∪[7..8]；摘要视图行仍输出一次（在 1 前）
        List<SurfaceInstruction> ops = List.of(
                SurfaceInstruction.replaceHead(1, 8, "全段摘要", "compaction"),
                SurfaceInstruction.restoreRange(2, 3, 6, null));

        List<SessionMessage> visible = projector.projectVisible(history, ops, 0, 100, 0);
        // 期望：摘要视图行 + msg_3..6 恢复可见
        assertTrue(visible.get(0).id().startsWith("surface_rep_"));
        assertEquals(5, visible.size());
        assertEquals("msg_3", visible.get(1).id());
        assertEquals("msg_6", visible.get(4).id());
    }
}
