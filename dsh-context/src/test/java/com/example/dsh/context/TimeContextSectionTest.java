package com.example.dsh.context;

import com.example.dsh.core.model.Agent;
import com.example.dsh.core.model.Session;
import com.example.dsh.core.model.SessionId;
import com.example.dsh.core.prompt.SystemPromptContext;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 时间上下文段测试。
 */
class TimeContextSectionTest {

    @Test
    void rendersCurrentTime() {
        TimeContextSection section = new TimeContextSection("2026-08-19 12:00:00 CST");
        SystemPromptContext ctx = SystemPromptContext.builder()
                .session(new Session(SessionId.of("s1"), null, null, null, Instant.now(), Instant.now()))
                .agent(new Agent("main", "A", "deepseek", "m", null, null))
                .toolRefs(java.util.List.of())
                .variables(java.util.Map.of())
                .build();
        String rendered = section.render(ctx);
        assertTrue(rendered.contains("2026-08-19 12:00:00 CST"));
    }
}
