package com.bizfty.anchon.dsh.core.prompt;

import com.bizfty.anchon.dsh.core.model.Agent;
import com.bizfty.anchon.dsh.core.model.Session;
import com.bizfty.anchon.dsh.core.model.SessionId;
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

    private final PromptTemplateRenderer templateRenderer = new CoreTestRenderer();

    @Test
    void assemblesOrderedSectionsWithInterpolation() {
        SystemPromptService service = new SystemPromptService(List.of(
                new FakeSection(50, "## 后段\n变量: {{sessionId}}"),
                new FakeSection(10, "## 前段\ncwd={{cwd}}")), templateRenderer);

        SystemPromptContext ctx = SystemPromptContext.builder()
                .session(session)
                .agent(agent)
                .toolRefs(List.of(new ToolRef("bash", "执行命令", "{}")))
                .variables(SystemPromptService.baseVariables(session, agent))
                .build();

        String prompt = service.assemble(ctx);

        int idxContext = prompt.indexOf("## 会话上下文");
        int idxPersona = prompt.indexOf("你是 Archon");
        int idxFront = prompt.indexOf("## 前段");
        int idxBack = prompt.indexOf("## 后段");
        assertTrue(idxContext < idxPersona && idxPersona < idxFront && idxFront < idxBack);
        assertTrue(prompt.contains("cwd=/workspace"));
        assertTrue(prompt.contains("变量: sess_1"));
        assertTrue(!prompt.contains("可用工具"));
    }

    @Test
    void unknownVariableFailsLoud() {
        SystemPromptService service = new SystemPromptService(
                List.of(new FakeSection(5, "引用缺失变量: {{missing_var}}")), templateRenderer);
        SystemPromptContext ctx = SystemPromptContext.builder()
                .session(session)
                .agent(agent)
                .toolRefs(List.of())
                .variables(SystemPromptService.baseVariables(session, agent))
                .build();
        assertThrows(SystemPromptService.PromptAssemblyException.class, () -> service.assemble(ctx));
    }

    @Test
    void sectionFailureIsWrapped() {
        SystemPromptService service = new SystemPromptService(List.of(new ThrowingSection()), templateRenderer);
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
        SystemPromptService service = new SystemPromptService(List.of(), templateRenderer);
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

    /**
     * 测试用模板渲染器 — 为 dsh-core 内建段提供内联模板。
     * 真实运行时由 SimplePromptRenderer 从 classpath 加载。
     */
    private static class CoreTestRenderer implements PromptTemplateRenderer {
        @Override
        public String render(String templatePath, Map<String, ?> variables) {
            return switch (templatePath) {
                case "prompt/context-section.txt" -> renderContext(variables);
                case "prompt/persona-section.txt" -> renderPersona(variables);
                case "prompt/interaction-conventions.txt" ->
                        "## 交互约定\n" +
                        "- 当需要用户做选择、确认偏好或提供决定时，使用 ask_user_question 工具提供选项并等待回答，不要在回答文本里直接罗列选项让用户回复。\n" +
                        "- 面向用户的选择应作为可点选选项（单选/多选），而非让用户手打文字。";
                default -> throw new IllegalArgumentException("未知模板: " + templatePath);
            };
        }

        private String renderContext(Map<String, ?> v) {
            StringBuilder sb = new StringBuilder("## 会话上下文\n");
            String cwd = str(v.get("cwd"));
            String sessionId = str(v.get("sessionId"));
            String model = str(v.get("model"));
            if (!cwd.isEmpty()) sb.append("- 工作目录: ").append(cwd).append('\n');
            if (!sessionId.isEmpty()) sb.append("- 会话: ").append(sessionId).append('\n');
            if (!model.isEmpty()) sb.append("- 模型: ").append(model).append('\n');
            return sb.toString();
        }

        private String renderPersona(Map<String, ?> v) {
            String persona = str(v.get("persona_text"));
            return persona;
        }

        private static String str(Object o) {
            return o != null ? o.toString() : "";
        }
    }
}