package com.bizfty.anchon.dsh.session;

import com.bizfty.anchon.dsh.core.model.MessageRole;
import com.bizfty.anchon.dsh.core.model.SessionId;
import com.bizfty.anchon.dsh.core.model.SessionMessage;
import com.bizfty.anchon.dsh.util.JsonUtils;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M8 性能烟雾（红线6：surface 空/少量指令读 seam 与现状同量级，无 O(n²) 回退）。
 * 宽松上界防 CI 抖动；目的：证明大消息量 + 中等遮蔽指令数下 projectVisible 线性可完成。
 */
class SurfaceProjectorPerfSmokeTest {

    private final JsonUtils jsonUtils = new JsonUtils();
    private final SurfaceProjector projector = new SurfaceProjector(jsonUtils);
    private final SessionId sid = SessionId.of("sess_perf");

    private List<SessionMessage> bigHistory(int n) {
        List<SessionMessage> list = new ArrayList<>(n);
        for (int i = 1; i <= n; i++) {
            list.add(new SessionMessage("msg_" + i, sid,
                    i % 2 == 1 ? MessageRole.USER : MessageRole.ASSISTANT,
                    "消息内容 " + i + " " + "x".repeat(50), null, null, null, i, Instant.now(), false));
        }
        return list;
    }

    private List<SurfaceInstruction> manyInstructions(int count, int size) {
        List<SurfaceInstruction> ops = new ArrayList<>();
        long gen = 1;
        int step = size / (count + 1);
        for (int i = 0; i < count; i++) {
            long from = (long) (i + 1) * step;
            long to = Math.min(size, from + step / 2);
            ops.add(new SurfaceInstruction(gen++, SurfaceOp.REPLACE_RANGE, from, to,
                    i % 3 == 0 ? "折叠" + i : null, "{\"reason\":\"perf\"}"));
        }
        return ops;
    }

    @Test
    void visibleProjectionStaysLinearAtScale() {
        int n = 20_000;
        List<SessionMessage> history = bigHistory(n);

        // 空 surface（legacy fast-path，与现状同实现）
        long t0 = System.nanoTime();
        for (int i = 0; i < 20; i++) {
            projector.projectVisible(history, List.of(), 300, 2000, 1);
        }
        long legacyMs = (System.nanoTime() - t0) / 1_000_000;

        // 少量遮蔽指令（150 段 REPLACE_RANGE）
        List<SurfaceInstruction> ops = manyInstructions(150, n);
        long t1 = System.nanoTime();
        for (int i = 0; i < 20; i++) {
            projector.projectVisible(history, ops, 0, 2000, 1);
        }
        long surfaceMs = (System.nanoTime() - t1) / 1_000_000;

        // 量级对照：surface 路径（每轮都重建 150 段遮蔽表）允许慢于 fast-path 至多 15x；
        // 两者都必须在线性量级（20 轮 × 2 万条在数秒内完成，非 O(n²) 分钟级）。
        assertTrue(legacyMs < 5_000, "legacy 20 轮应 < 5s，实际 " + legacyMs + "ms");
        assertTrue(surfaceMs < 10_000, "surface 20 轮应 < 10s，实际 " + surfaceMs + "ms");
        assertTrue(surfaceMs <= Math.max(2_000, legacyMs * 15),
                "surface 路径不得比 legacy 慢超 15x（legacy=" + legacyMs + "ms surface=" + surfaceMs + "ms）");
    }
}
