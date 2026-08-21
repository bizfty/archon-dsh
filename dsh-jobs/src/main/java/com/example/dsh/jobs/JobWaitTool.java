package com.example.dsh.jobs;

import com.example.dsh.tool.AgentTool;
import com.example.dsh.tool.Tool;
import com.example.dsh.tool.ToolCall;
import com.example.dsh.tool.ToolContext;
import com.example.dsh.tool.ToolResult;
import com.example.dsh.tool.ToolSchema;

import java.util.Map;
import java.util.Optional;

/**
 * job_wait 工具 — 等待后台任务完成并返回输出（对应 DSH jobs/tool-jobs 的 wait/read）。
 */
@Tool(name = "job_wait", description = "等待一个后台任务完成（最多 timeout_ms），返回最终状态与输出。")
public class JobWaitTool implements AgentTool {

    private final JobService jobService;

    public JobWaitTool(JobService jobService) {
        this.jobService = jobService;
    }

    @Override
    public String name() {
        return "job_wait";
    }

    @Override
    public ToolSchema getSchema() {
        return ToolSchema.builder()
                .name(name())
                .description("等待后台任务。")
                .addParameter("job_id", "string", "任务 id")
                .addParameter("timeout_ms", "integer", "等待上限（默认 30000）")
                .required("job_id")
                .build();
    }

    @Override
    public ToolResult execute(ToolCall call, ToolContext context) {
        String jobId = call.getString("job_id");
        if (jobId == null || jobId.isBlank()) {
            return ToolResult.failure("缺少必要参数 job_id");
        }
        if (context.sessionId() == null) {
            return ToolResult.failure("缺少 sessionId");
        }
        int timeoutMs = call.getInt("timeout_ms", 30_000);
        Optional<JobHandle> done = jobService.waitFor(context.sessionId(), jobId, timeoutMs);
        if (done.isEmpty()) {
            return ToolResult.failure("等待超时（>" + timeoutMs + " ms），任务仍在运行，可用 job_status 再查");
        }
        JobHandle job = done.get();
        String output = job.output() == null ? "" : job.output();
        String tail = output.length() > 3000 ? "…" + output.substring(output.length() - 3000) : output;
        return ToolResult.success("任务 " + job.id() + " 状态=" + job.status()
                + (job.exitCode() != null ? " exit=" + job.exitCode() : "") + "\n" + tail,
                Map.of("status", job.status().name(),
                        "exit_code", job.exitCode() == null ? 0 : job.exitCode(),
                        "output", tail));
    }
}
