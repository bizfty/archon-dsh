package com.bizfty.anchon.dsh.agent.command;

import com.bizfty.anchon.dsh.core.model.SessionId;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M6-1 命令注册表（design §3.2/§8）：Spring 收集保序；/<name> 识别与 args 透传；
 * /help 内置列命令；未知命令与普通消息返回 null（走 agent 正常流程）。
 */
class CommandRegistryTest {

    private final SessionId sessionId = SessionId.of("sess_cmd");

    private static final class StubCommand implements ChatCommand {
        private final String name;
        private final AtomicReference<String> lastArgs = new AtomicReference<>();

        StubCommand(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return "stub " + name;
        }

        @Override
        public String execute(SessionId sid, String args) {
            lastArgs.set(args);
            return name + ":" + args;
        }
    }

    @Test
    void resolvesCommandByNameAndPassesArgs() {
        StubCommand ping = new StubCommand("ping");
        CommandRegistry registry = new CommandRegistry(List.of(ping));

        assertEquals("ping:", registry.execute(sessionId, "/ping"));
        assertEquals("", ping.lastArgs.get());
        assertEquals("ping:hello world", registry.execute(sessionId, "/ping hello world"));
        assertEquals("hello world", ping.lastArgs.get());
    }

    @Test
    void unknownSlashAndPlainMessageReturnNull() {
        CommandRegistry registry = new CommandRegistry(List.of(new StubCommand("ping")));
        assertNull(registry.execute(sessionId, "/nosuch"));
        assertNull(registry.execute(sessionId, "普通聊天消息 /ping 不应当命令"));
        assertNull(registry.execute(sessionId, null));
        assertNull(registry.execute(sessionId, "  /"));
    }

    @Test
    void helpIsBuiltInAndListsCommands() {
        CommandRegistry registry = new CommandRegistry(List.of(new StubCommand("compact")));
        String help = registry.execute(sessionId, "/help");
        assertTrue(help.contains("/help - 列出可用命令"), help);
        assertTrue(help.contains("/compact - stub compact"), help);
    }

    @Test
    void compactCommandDelegatesToManualCompactAndRejectsArgs() {
        com.bizfty.anchon.dsh.agent.AgentLoopService loop =
                org.mockito.Mockito.mock(com.bizfty.anchon.dsh.agent.AgentLoopService.class);
        org.mockito.Mockito.when(loop.manualCompact(sessionId)).thenReturn("已压缩 3 条历史");
        CompactChatCommand cmd = new CompactChatCommand(loop);

        assertEquals("已压缩 3 条历史", cmd.execute(sessionId, ""));
        assertEquals("Usage: /compact (no arguments)", cmd.execute(sessionId, "x"));
        org.mockito.Mockito.verify(loop).manualCompact(sessionId);
    }
}
