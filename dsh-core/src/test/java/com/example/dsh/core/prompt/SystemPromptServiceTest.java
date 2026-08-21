package com.example.dsh.core.prompt;

import com.example.dsh.core.model.Agent;
import com.example.dsh.core.model.Session;
import com.example.dsh.core.model.SessionId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * system-prompt 组装测试：段顺序、变量插值、未知变量 fail loud。
 */
class SystemPromptServiceTest {

    private final Session session = new Session(
            SessionId.of("sess_1"), "测试", "deepseek-chat", "/workspace", Instant.now(), Instant.now());

    private final Agent agent = new Agent("main", "Archon", "deepseek", "deepseek-chat", null, null);

    @Test
    void assemblesOrderedSectionsWithInterpolation() {
        SystemPromptService service = new SystemPromptService(List.of(
                new FakeSection(50, "## 后段\n变量: {{sessionId}}"),
                new FakeSection(10, "## 前段\ncwd={{cwd}}")));

        SystemPromptContext ctx = SystemPromptContext.builder()
                .session(session)
                .agent(agent)
                .toolRefs(List.of(new ToolRef("bash", "执行命令", "{}")))
                .variables(SystemPromptService.baseVariables(session, agent))
                .build();

        String prompt = service.assemble(ctx);

        // 内建段在前，注册段按 order 升序
        int idxContext = prompt.indexOf("## 会话上下文");
        int idxPersona = prompt.indexOf("你是 Archon");
        int idxFront = prompt.indexOf("## 前段");
        int idxBack = prompt.indexOf("## 后段");
        assertTrue(idxContext < idxPersona && idxPersona < idxFront && idxFront < idxBack);
        // 变量插值
        assertTrue(prompt.contains("cwd=/workspace"));
        assertTrue(prompt.contains("变量: sess_1"));
        // 工具指导段由 dsh-tool 注册，此处不注入
        assertTrue(!prompt.contains("可用工具"));
    }

    @Test
    void unknownVariableFailsLoud() {
        SystemPromptService service = new SystemPromptService(
                List.of(new FakeSection(5, "引用缺失变量: {{missing_var}}")));
        SystemPromptContext ctx = SystemPromptContext.builder()
                .session(session)
                .agent(agent)
                .toolRefs(List.of())
                .variables(SystemPromptService.baseVariables(session, agent))
                .build();
        // baseVariables 不包含 missing_var → 严格插值应抛异常
        assertThrows(SystemPromptService.PromptAssemblyException.class, () -> service.assemble(ctx));
    }

    @Test
    void sectionFailureIsWrapped() {
        SystemPromptService service = new SystemPromptService(List.of(new ThrowingSection()));
        SystemPromptContext ctx = SystemPromptContext.builder()
                .session(session)
                .agent(agent)
                .toolRefs(List.of())
                .variables(SystemPromptService.baseVariables(session, agent))
                .build();
        assertThrows(SystemPromptService.PromptAssemblyException.class, () -> service.assemble(ctx));
    }

    @Test
    void emptySectionsProduceBasePrompt() {
        SystemPromptService service = new SystemPromptService(List.of());
        SystemPromptContext ctx = SystemPromptContext.builder()
                .session(session)
                .agent(agent)
                .toolRefs(List.of())
                .variables(SystemPromptService.baseVariables(session, agent))
                .build();
        String prompt = service.assemble(ctx);
        assertTrue(prompt.contains("你是 Archon"));
        assertEquals("deepseek-chat", ctx.variables().get("model"));
    }

    private record FakeSection(int order, String text) implements SystemPromptSection {
        @Override
        public int order() {
            return order;
        }

        @Override
        public String render(SystemPromptContext context) {
            return text;
        }
    }

    private static final class ThrowingSection implements SystemPromptSection {
        @Override
        public int order() {
            return 5;
        }

        @Override
        public String render(SystemPromptContext context) {
            throw new IllegalStateException("boom");
        }
    }
}
