package com.bizfty.anchon.dsh.agent;

import com.bizfty.anchon.dsh.compaction.ToolResultPruneProperties;
import com.bizfty.anchon.dsh.compaction.ToolResultPruner;
import com.bizfty.anchon.dsh.core.model.MessageRole;
import com.bizfty.anchon.dsh.core.model.SessionId;
import com.bizfty.anchon.dsh.core.model.SessionMessage;
import com.bizfty.anchon.dsh.util.JsonUtils;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 消息投影 + 工具结果截断集成测试：注入 pruner 时 TOOL 投影截断超大结果，
 * 其他角色不受影响；无 pruner 时原样。
 */
class MessageProjectorPruneTest {

    private final SessionId sessionId = SessionId.of("s_prune");

    private SessionMessage toolMessage(String content) {
        return new SessionMessage("m1", sessionId, MessageRole.TOOL, content, "call_1", "bash",
                null, 1, Instant.now());
    }

    private SessionMessage userMessage(String content) {
        return new SessionMessage("m2", sessionId, MessageRole.USER, content, null, null,
                null, 2, Instant.now());
    }

    @Test
    void toolProjectionPrunesOversizedResult() {
        ToolResultPruner pruner = new ToolResultPruner(new ToolResultPruneProperties(100, 40, 20));
        MessageProjector projector = new MessageProjector(new JsonUtils(), pruner);

        String huge = "A".repeat(50) + "x".repeat(5000) + "Z".repeat(50);
        Message message = projector.project(toolMessage(huge));
        assertTrue(message instanceof ToolResponseMessage);
        String text = ((ToolResponseMessage) message).getResponses().get(0).responseData();
        assertNotEquals(huge, text, "超大工具结果应被截断");
        assertTrue(text.startsWith("A".repeat(40)));
        assertTrue(text.endsWith("Z".repeat(20)));
        assertTrue(text.contains(ToolResultPruner.PRUNE_MARKER));
        assertTrue(text.length() < 200, "截断后远小于原文");
    }

    @Test
    void smallToolResultUnchangedWithPruner() {
        ToolResultPruner pruner = new ToolResultPruner(new ToolResultPruneProperties(100, 40, 20));
        MessageProjector projector = new MessageProjector(new JsonUtils(), pruner);
        String small = "ok";
        Message message = projector.project(toolMessage(small));
        assertEquals(small, ((ToolResponseMessage) message).getResponses().get(0).responseData());
    }

    @Test
    void noPrunerLeavesToolResultUntouched() {
        MessageProjector projector = new MessageProjector(new JsonUtils());
        String huge = "x".repeat(100_000);
        Message message = projector.project(toolMessage(huge));
        assertEquals(huge, ((ToolResponseMessage) message).getResponses().get(0).responseData(),
                "未注入 pruner 时原样投影");
    }

    @Test
    void nonToolRolesUnaffectedByPruner() {
        ToolResultPruner pruner = new ToolResultPruner(new ToolResultPruneProperties(200, 40, 20));
        MessageProjector projector = new MessageProjector(new JsonUtils(), pruner);
        String longText = "y".repeat(10_000);
        Message user = projector.project(userMessage(longText));
        assertEquals(longText, ((org.springframework.ai.chat.messages.UserMessage) user).getText(),
                "USER 消息不截断（pruner 只管工具结果）");
    }
}