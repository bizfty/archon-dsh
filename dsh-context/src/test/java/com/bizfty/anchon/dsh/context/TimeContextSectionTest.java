package com.bizfty.anchon.dsh.context;

import com.bizfty.anchon.dsh.core.model.Agent;
import com.bizfty.anchon.dsh.core.model.Session;
import com.bizfty.anchon.dsh.core.model.SessionId;
import com.bizfty.anchon.dsh.core.prompt.PromptTemplateRenderer;
import com.bizfty.anchon.dsh.core.prompt.SystemPromptContext;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TimeContextSectionTest {

    @Test
    void rendersCurrentTime() {
        PromptTemplateRenderer renderer = new TestTemplateRenderer(Map.of(
                "prompt/time-context.txt", "当前时间: ${now}"));
        TimeContextSection section = new TimeContextSection(renderer, "2026-08-19 12:00:00 CST");
        SystemPromptContext ctx = SystemPromptContext.builder()
                .session(new Session(SessionId.of("s1"), null, null, null, Instant.now(), Instant.now()))
                .agent(new Agent("main", "A", "deepseek", "m", null, null))
                .toolRefs(java.util.List.of())
                .variables(java.util.Map.of())
                .build();
        String rendered = section.render(ctx);
        assertTrue(rendered.contains("2026-08-19 12:00:00 CST"));
    }

    @Test
    void rendersFromTemplate() {
        PromptTemplateRenderer renderer = new TestTemplateRenderer(Map.of(
                "prompt/time-context.txt", "当前时间: ${now}\n(请尽快响应)"));
        TimeContextSection section = new TimeContextSection(renderer, "2026-08-19 12:00:00 CST");
        SystemPromptContext ctx = SystemPromptContext.builder()
                .session(new Session(SessionId.of("s1"), null, null, null, Instant.now(), Instant.now()))
                .agent(new Agent("main", "A", "deepseek", "m", null, null))
                .toolRefs(java.util.List.of())
                .variables(java.util.Map.of())
                .build();
        String rendered = section.render(ctx);
        assertTrue(rendered.contains("请尽快响应"));
    }

    private static class TestTemplateRenderer implements PromptTemplateRenderer {
        private final Map<String, String> templates;

        TestTemplateRenderer(Map<String, String> templates) {
            this.templates = templates;
        }

        @Override
        public String render(String templatePath, Map<String, ?> variables) {
            String template = templates.get(templatePath);
            if (template == null) {
                throw new IllegalArgumentException("未知模板: " + templatePath);
            }
            String result = template;
            for (Map.Entry<String, ?> entry : variables.entrySet()) {
                result = result.replace("${" + entry.getKey() + "}",
                        entry.getValue() != null ? entry.getValue().toString() : "");
            }
            return result;
        }
    }
}