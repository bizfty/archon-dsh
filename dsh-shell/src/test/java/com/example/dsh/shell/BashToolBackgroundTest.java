package com.example.dsh.shell;

import com.example.dsh.core.model.SessionId;
import com.example.dsh.jobs.JobHandle;
import com.example.dsh.jobs.JobService;
import com.example.dsh.jobs.JobStatus;
import com.example.dsh.tool.ToolCall;
import com.example.dsh.tool.ToolContext;
import com.example.dsh.tool.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * bash 后台模式测试：run_in_background 返回 job_id，任务可经 JobService 查询。
 */
class BashToolBackgroundTest {

    @Test
    void backgroundModeReturnsJobId() {
        JobService jobService = new JobService(1_048_576);
        BashExecutor executor = new BashExecutor();
        BashTool tool = new BashTool(executor, jobService);
        SessionId sessionId = SessionId.of("sess_bg");

        ToolResult result = tool.execute(new ToolCall("call_1", "bash",
                Map.of("command", "echo bg-ok", "run_in_background", true)),
                ToolContext.builder().sessionId(sessionId).build());

        assertTrue(result.success());
        String jobId = (String) result.data().get("job_id");
        assertTrue(jobId != null && !jobId.isBlank(), "应返回 job_id");
        // 任务实际完成且输出正确
        JobHandle done = jobService.waitFor(sessionId, jobId, 10_000).orElseThrow();
        assertEquals(JobStatus.DONE, done.status());
        assertTrue(done.output().contains("bg-ok"));
    }

    @Test
    void foregroundModeStillWorks() {
        JobService jobService = new JobService(1_048_576);
        BashExecutor executor = new BashExecutor();
        BashTool tool = new BashTool(executor, jobService);

        ToolResult result = tool.execute(new ToolCall("call_1", "bash",
                Map.of("command", "echo fg")), ToolContext.builder().build());

        assertTrue(result.success());
        assertTrue(result.message().contains("fg"));
        assertEquals(0, result.data().get("exit_code"));
    }

    @Test
    void readOnlyModeDeniesExecution() {
        JobService jobService = new JobService(1_048_576);
        BashTool tool = new BashTool(new BashExecutor(), jobService);
        ToolResult result = tool.execute(new ToolCall("call_1", "bash",
                Map.of("command", "echo should-not-run")),
                ToolContext.builder().sandboxMode(com.example.dsh.tool.SandboxMode.READ_ONLY).build());
        assertFalse(result.success());
        assertTrue(result.message().contains("只读"));
    }
}
