package com.bizfty.anchon.dsh.jobs;

import com.bizfty.anchon.dsh.tool.AgentTool;
import com.bizfty.anchon.dsh.tool.Tool;
import com.bizfty.anchon.dsh.tool.ToolCall;
import com.bizfty.anchon.dsh.tool.ToolContext;
import com.bizfty.anchon.dsh.tool.ToolResult;
import com.bizfty.anchon.dsh.tool.ToolSchema;

import java.util.Optional;

/**
 * job_status 工具 — 查询后台任务状态与输出（对应 DSH jobs/tool-jobs 的状态面）。
 */
@Tool(name = "job_status", description = "查询后台任务的状态与输出。")
public class JobStatusTool implements AgentTool {

    private final JobService jobService;

    public JobStatusTool(JobService jobService) {
        this.jobService = jobService;
    }

    @Override
    public String name() {
        return "job_status";
    }

    @Override
    public ToolSchema getSchema() {
        return ToolSchema.builder()
                .name(name())
                .description("查询后台任务。")
                .addParameter("job_id", "string", "任务 id")
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
        Optional<JobHandle> handle = jobService.get(context.sessionId(), jobId);
        if (handle.isEmpty()) {
            return ToolResult.failure("任务不存在: " + jobId + "（可能不属于当前会话，或用 job 列表确认）");
        }
        JobHandle job = handle.get();
        String output = job.output() == null ? "" : job.output();
        String tail = output.length() > 2000 ? "…" + output.substring(output.length() - 2000) : output;
        String message = "任务 " + job.id() + " [" + job.kind() + "] 状态=" + job.status()
                + (job.exitCode() != null ? " exit=" + job.exitCode() : "")
                + "\n" + tail;
        return ToolResult.success(message, java.util.Map.of(
                "status", job.status().name(),
                "exit_code", job.exitCode() == null ? 0 : job.exitCode(),
                "output", tail));
    }
}
