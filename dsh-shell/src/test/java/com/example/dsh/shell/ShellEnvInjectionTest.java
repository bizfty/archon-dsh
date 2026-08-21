package com.example.dsh.shell;

import com.example.dsh.core.model.SessionId;
import com.example.dsh.tool.ToolCall;
import com.example.dsh.tool.ToolContext;
import com.example.dsh.tool.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * shell-env 受管变量测试：DSH_SESSION_ID / DSH_WORKDIR 注入子进程（对应 DSH shell-env）。
 */
class ShellEnvInjectionTest {

    @Test
    void injectsSessionIdIntoChildProcess() {
        BashExecutor executor = new BashExecutor();
        SessionId sessionId = SessionId.of("sess_env123");

        BashExecutor.BashResult result = executor.run(
                "echo SESSION=$DSH_SESSION_ID WORKDIR=$DSH_WORKDIR", "/tmp",
                10_000, Map.of(
                        "DSH_SESSION_ID", sessionId.value(),
                        "DSH_WORKDIR", "/tmp"));

        assertTrue(result.success());
        assertTrue(result.output().contains("SESSION=sess_env123"), "应注入 DSH_SESSION_ID: " + result.output());
        assertTrue(result.output().contains("WORKDIR=/tmp"), "应注入 DSH_WORKDIR: " + result.output());
    }

    @Test
    void bashToolForegroundInjectsManagedEnv() {
        BashExecutor executor = new BashExecutor();
        com.example.dsh.jobs.JobService jobService = new com.example.dsh.jobs.JobService(1_048_576);
        BashTool tool = new BashTool(executor, jobService);

        ToolResult result = tool.execute(new ToolCall("c1", "bash",
                Map.of("command", "echo S=$DSH_SESSION_ID")),
                ToolContext.builder().sessionId(SessionId.of("sess_xyz")).build());

        assertTrue(result.success());
        assertTrue(result.message().contains("S=sess_xyz"), "工具级也应注入: " + result.message());
    }
}
