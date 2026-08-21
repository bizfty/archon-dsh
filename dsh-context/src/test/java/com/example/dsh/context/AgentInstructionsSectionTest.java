package com.example.dsh.context;

import com.example.dsh.core.model.Agent;
import com.example.dsh.core.model.Session;
import com.example.dsh.core.model.SessionId;
import com.example.dsh.core.prompt.SystemPromptContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工作区指令测试：查找/注入/内容去重/禁用。
 */
class AgentInstructionsSectionTest {

    private final Session session = new Session(SessionId.of("sess_1"), "t", null, null,
            Instant.now(), Instant.now());
    private final Agent agent = new Agent("main", "Archon", "deepseek", "m", null, null);

    private SystemPromptContext contextWith(Path cwd) {
        return SystemPromptContext.builder()
                .session(new Session(session.id(), session.title(), session.model(),
                        cwd == null ? null : cwd.toString(), session.createdAt(), session.updatedAt()))
                .agent(agent)
                .toolRefs(java.util.List.of())
                .variables(java.util.Map.of())
                .build();
    }

    @Test
    void injectsAgentsMdFromCwd(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("AGENTS.md"), "# 项目规则\n- 用中文回复\n");
        AgentInstructionsService service = new AgentInstructionsService(true, 65536);
        AgentInstructionsSection section = new AgentInstructionsSection(service);

        String rendered = section.render(contextWith(dir));
        assertTrue(rendered.contains("项目规则"));
        assertTrue(rendered.contains("用中文回复"));
    }

    @Test
    void emptyWhenNoInstructionFile(@TempDir Path dir) {
        AgentInstructionsService service = new AgentInstructionsService(true, 65536);
        AgentInstructionsSection section = new AgentInstructionsSection(service);
        assertEquals("", section.render(contextWith(dir)));
    }

    @Test
    void dedupesIdenticalContentAcrossFiles(@TempDir Path dir) throws IOException {
        String content = "# 相同内容\n";
        Files.writeString(dir.resolve("AGENTS.md"), content);
        Files.writeString(dir.resolve("CLAUDE.md"), content);
        AgentInstructionsService service = new AgentInstructionsService(true, 65536);
        var instructions = service.find(dir.toString());
        assertEquals(1, instructions.size(), "相同内容只注入一次");
    }

    @Test
    void disabledReturnsNothing(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("AGENTS.md"), "# 规则\n");
        AgentInstructionsService service = new AgentInstructionsService(false, 65536);
        assertTrue(service.find(dir.toString()).isEmpty());
    }

    @Test
    void searchesParentDirectories(@TempDir Path parent) throws IOException {
        Path nested = Files.createDirectories(parent.resolve("a/b"));
        Files.writeString(parent.resolve("AGENTS.md"), "# 上级规则\n");
        AgentInstructionsService service = new AgentInstructionsService(true, 65536);
        var instructions = service.find(nested.toString());
        assertEquals(1, instructions.size());
        assertTrue(instructions.get(0).content().contains("上级规则"));
    }
}
