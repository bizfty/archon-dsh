package com.bizfty.anchon.dsh.hooks;

import com.bizfty.anchon.dsh.util.JsonUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hook 配置与执行器测试：配置解析、matcher 语义、决策协议（continue/block/ask/异常）。
 */
class HooksTest {

    @TempDir
    Path dir;

    private HookConfig configWith(String json) throws Exception {
        Path file = dir.resolve("hooks.json");
        Files.writeString(file, json);
        return new HookConfig(file.toString());
    }

    @Test
    void parsesClaudeCodeStyleConfig() throws Exception {
        HookConfig config = configWith("""
                {"hooks": {
                  "PreToolUse": [{"matcher": "bash", "hooks": [{"type": "command", "command": "echo pre"}]}],
                  "PostToolUse": [{"matcher": "*", "hooks": [{"command": "echo post"}]}]
                }}""");
        assertEquals(List.of("echo pre"), config.commandsFor("PreToolUse", "bash"));
        assertEquals(List.of(), config.commandsFor("PreToolUse", "read_file"), "matcher 不匹配");
        assertEquals(List.of("echo post"), config.commandsFor("PostToolUse", "anything"));
        assertTrue(config.hasAny());
    }

    @Test
    void matcherPrefixSemantics() throws Exception {
        HookConfig config = configWith("""
                {"hooks": {"PreToolUse": [{"matcher": "read", "hooks": [{"command": "echo r"}]}]}}""");
        assertEquals(List.of("echo r"), config.commandsFor("PreToolUse", "read_file"), "前缀匹配");
        assertEquals(List.of(), config.commandsFor("PreToolUse", "bash"));
    }

    @Test
    void missingConfigFileMeansNoHooks() {
        HookConfig config = new HookConfig(dir.resolve("absent.json").toString());
        assertFalse(config.hasAny());
        assertEquals(List.of(), config.commandsFor("PreToolUse", "bash"));
    }

    @Test
    void runnerContinueAllows() {
        HookRunner runner = new HookRunner(new JsonUtils(), 5000);
        Optional<String> result = runner.run("PreToolUse", "bash", java.util.Map.of("cmd", "ls"),
                "s1", dir.toString(), "printf '{\"decision\":\"continue\"}'");
        assertTrue(result.isEmpty(), "continue 应放行");
    }

    @Test
    void runnerBlockReturnsReason() {
        HookRunner runner = new HookRunner(new JsonUtils(), 5000);
        Optional<String> result = runner.run("PreToolUse", "bash", java.util.Map.of(),
                "s1", dir.toString(), "printf '{\"decision\":\"block\",\"reason\":\"不安全\"}'");
        assertTrue(result.isPresent());
        assertEquals("不安全", result.get());
    }

    @Test
    void runnerAskMapsToUserQuestion() {
        HookRunner runner = new HookRunner(new JsonUtils(), 5000);
        Optional<String> result = runner.run("PreToolUse", "bash", java.util.Map.of(),
                "s1", dir.toString(), "printf '{\"decision\":\"ask\",\"question\":\"确认执行?\"}'");
        assertTrue(result.isPresent());
        assertTrue(result.get().contains("确认执行?"));
        assertTrue(result.get().contains("ask_user_question"));
    }

    @Test
    void runnerNonJsonOutputBlocks() {
        HookRunner runner = new HookRunner(new JsonUtils(), 5000);
        Optional<String> result = runner.run("PreToolUse", "bash", java.util.Map.of(),
                "s1", dir.toString(), "printf 'hello'");
        assertTrue(result.isPresent());
        assertTrue(result.get().contains("非 JSON"));
    }

    @Test
    void runnerNonZeroExitBlocks() {
        HookRunner runner = new HookRunner(new JsonUtils(), 5000);
        Optional<String> result = runner.run("PreToolUse", "bash", java.util.Map.of(),
                "s1", dir.toString(), "printf 'ignored'; exit 3");
        assertTrue(result.isPresent());
        assertTrue(result.get().contains("退出码"));
    }

    @Test
    void runnerTimeoutBlocks() {
        HookRunner runner = new HookRunner(new JsonUtils(), 300);
        Optional<String> result = runner.run("PreToolUse", "bash", java.util.Map.of(),
                "s1", dir.toString(), "sleep 5");
        assertTrue(result.isPresent());
        assertTrue(result.get().contains("超时"));
    }

    @Test
    void runnerReceivesStdinPayload() throws Exception {
        // hook 回显 stdin 中的 tool_name
        HookRunner runner = new HookRunner(new JsonUtils(), 5000);
        String command = "python3 -c \"import sys,json; d=json.load(sys.stdin); print(json.dumps({'decision':'block','reason':'tool='+d['tool_name']}))\"";
        Optional<String> result = runner.run("PreToolUse", "bash", java.util.Map.of("a", 1),
                "s1", dir.toString(), command);
        assertTrue(result.isPresent());
        assertEquals("tool=bash", result.get(), "hook 应收到 stdin 事件 JSON");
    }
}
