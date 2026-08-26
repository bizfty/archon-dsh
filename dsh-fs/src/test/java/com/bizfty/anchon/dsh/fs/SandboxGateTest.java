package com.bizfty.anchon.dsh.fs;

import com.bizfty.anchon.dsh.tool.SandboxMode;
import com.bizfty.anchon.dsh.tool.ToolCall;
import com.bizfty.anchon.dsh.tool.ToolContext;
import com.bizfty.anchon.dsh.tool.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 沙箱门控测试：read-only 拒绝写、workspace 限制、danger 放行。
 */
class SandboxGateTest {

    @TempDir
    Path workspace;

    @Test
    void readOnlyBlocksWrites() throws Exception {
        WriteFileTool tool = new WriteFileTool();
        Path file = workspace.resolve("f.txt");
        ToolResult result = tool.execute(
                new ToolCall("c1", "write_file", Map.of("path", file.toString(), "content", "x")),
                ToolContext.builder().cwd(workspace.toString()).sandboxMode(SandboxMode.READ_ONLY).build());
        assertFalse(result.success());
        assertTrue(result.message().contains("只读"));
        assertFalse(Files.exists(file), "只读模式下不应写文件");
    }

    @Test
    void workspaceWriteAllowsWithinWorkspace() {
        WriteFileTool tool = new WriteFileTool();
        Path file = workspace.resolve("ok.txt");
        ToolResult result = tool.execute(
                new ToolCall("c1", "write_file", Map.of("path", file.toString(), "content", "hi")),
                ToolContext.builder().cwd(workspace.toString()).sandboxMode(SandboxMode.WORKSPACE_WRITE).build());
        assertTrue(result.success(), "工作区内写应放行: " + result.message());
    }

    @Test
    void workspaceWriteBlocksOutside() {
        WriteFileTool tool = new WriteFileTool();
        ToolResult result = tool.execute(
                new ToolCall("c1", "write_file",
                        Map.of("path", "/tmp/dsh-outside-test-" + System.nanoTime() + ".txt", "content", "x")),
                ToolContext.builder().cwd(workspace.toString()).sandboxMode(SandboxMode.WORKSPACE_WRITE).build());
        assertFalse(result.success());
        assertTrue(result.message().contains("工作区"));
    }

    @Test
    void dangerFullAccessAllowsOutside() throws Exception {
        WriteFileTool tool = new WriteFileTool();
        Path outside = Files.createTempDirectory("dsh-danger").resolve("f.txt");
        ToolResult result = tool.execute(
                new ToolCall("c1", "write_file", Map.of("path", outside.toString(), "content", "x")),
                ToolContext.builder().cwd(workspace.toString()).sandboxMode(SandboxMode.DANGER_FULL_ACCESS).build());
        assertTrue(result.success(), "danger 模式应放行: " + result.message());
    }

    @Test
    void nullModeDefaultsToWorkspaceWrite() {
        WriteFileTool tool = new WriteFileTool();
        ToolResult result = tool.execute(
                new ToolCall("c1", "write_file", Map.of("path", "/tmp/x-" + System.nanoTime() + ".txt", "content", "x")),
                ToolContext.builder().cwd(workspace.toString()).build());
        assertFalse(result.success(), "null 模式按 workspace-write 应拒绝工作区外");
    }
}
