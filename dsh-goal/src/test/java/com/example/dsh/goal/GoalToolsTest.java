package com.example.dsh.goal;

import com.example.dsh.core.model.Session;
import com.example.dsh.core.model.SessionId;
import com.example.dsh.core.prompt.SystemPromptContext;
import com.example.dsh.storage.InMemoryStorageBackend;
import com.example.dsh.storage.StorageService;
import com.example.dsh.tool.ToolContext;
import com.example.dsh.tool.ToolResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 目标工具 + prompt 段测试：create/update/get 工具执行、CAS 语义、prompt 注入与空目标。
 */
class GoalToolsTest {

    private GoalService service() {
        @SuppressWarnings("unchecked")
        ObjectProvider<com.example.dsh.storage.StorageBackend> sp = mock(ObjectProvider.class);
        when(sp.orderedStream()).thenReturn(Stream.of(new InMemoryStorageBackend()));
        return new GoalService(new StorageService(sp));
    }

    private ToolContext ctx(String sessionId) {
        return ToolContext.builder().sessionId(SessionId.of(sessionId)).build();
    }

    private com.example.dsh.tool.ToolCall call(String... kv) {
        java.util.Map<String, Object> args = new java.util.LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            args.put(kv[i], kv[i + 1]);
        }
        return new com.example.dsh.tool.ToolCall("call_1", "goal", args);
    }

    @Test
    void createToolMakesGoalAndGetToolReadsIt() {
        GoalService goals = service();
        var create = new GoalTools.CreateGoalTool(goals);
        ToolResult created = create.execute(call("objective", "复刻 DSH 能力"), ctx("s1"));
        assertTrue(created.success());
        assertTrue(created.message().contains("goal-"));

        var get = new GoalTools.GetGoalTool(goals);
        ToolResult got = get.execute(call(), ctx("s1"));
        assertTrue(got.success());
        assertTrue(got.message().contains("复刻 DSH 能力"));
        assertTrue(got.message().contains("revision=1"));
    }

    @Test
    void updateToolCompletesWithCas() {
        GoalService goals = service();
        new GoalTools.CreateGoalTool(goals).execute(call("objective", "A", "maxGoalRounds", "5"), ctx("s2"));
        var get = new GoalTools.GetGoalTool(goals);
        ToolResult got = get.execute(call(), ctx("s2"));
        String id = extract(got.message(), "id=", " revision");
        String rev = extract(got.message(), "revision=", " phase");

        var update = new GoalTools.UpdateGoalTool(goals);
        ToolResult done = update.execute(call(
                "goal_id", id, "revision", rev, "action", "complete"), ctx("s2"));
        assertTrue(done.success());
        assertTrue(done.message().contains("phase=complete"));

        // 旧 revision 再更新 → CAS 拒绝
        ToolResult stale = update.execute(call(
                "goal_id", id, "revision", rev, "action", "pause"), ctx("s2"));
        assertTrue(!stale.success(), "旧 revision 应被 CAS 拒绝");
        assertTrue(stale.message().contains("不匹配"));
    }

    @Test
    void blockedToolRequiresCodeAndReason() {
        GoalService goals = service();
        var create = new GoalTools.CreateGoalTool(goals);
        create.execute(call("objective", "B"), ctx("s3"));
        var update = new GoalTools.UpdateGoalTool(goals);
        var get = new GoalTools.GetGoalTool(goals);
        String got = get.execute(call(), ctx("s3")).message();
        ToolResult noReason = update.execute(call(
                "goal_id", extract(got, "id=", " revision"),
                "revision", extract(got, "revision=", " phase"),
                "action", "blocked", "blockedCode", "missing-dep"), ctx("s3"));
        assertTrue(!noReason.success(), "blocked 缺 reason 应失败");
    }

    @Test
    void promptSectionRendersGoalWhenPresent() {
        GoalService goals = service();
        goals.create("s4", "完成端到端验证", 3);
        GoalPromptSection section = new GoalPromptSection(goals);
        Session session = new Session(SessionId.of("s4"), "t", "m", "/w", Instant.now(), Instant.now());
        String text = section.render(SystemPromptContext.builder().session(session).build());
        assertTrue(text.contains("当前目标: 完成端到端验证"));
        assertTrue(text.contains("phase=active"));
        assertTrue(text.contains("rounds=0/3"));
    }

    @Test
    void promptSectionEmptyWithoutGoal() {
        GoalService goals = service();
        GoalPromptSection section = new GoalPromptSection(goals);
        Session session = new Session(SessionId.of("s5"), "t", "m", "/w", Instant.now(), Instant.now());
        assertEquals("", section.render(SystemPromptContext.builder().session(session).build()));
    }

    private static String extract(String text, String prefix, String suffix) {
        int start = text.indexOf(prefix) + prefix.length();
        int end = text.indexOf(suffix, start);
        return text.substring(start, end);
    }
}
