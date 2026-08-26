package com.bizfty.anchon.dsh.jobs;

import com.bizfty.anchon.dsh.tool.AgentTool;
import com.bizfty.anchon.dsh.tool.Tool;
import com.bizfty.anchon.dsh.tool.ToolCall;
import com.bizfty.anchon.dsh.tool.ToolContext;
import com.bizfty.anchon.dsh.tool.ToolResult;
import com.bizfty.anchon.dsh.tool.ToolSchema;

/**
 * job_kill 工具 — 终止后台任务（对应 DSH jobs/tool-jobs 的 kill）。
 */
@Tool(name = "job_kill", description = "终止一个后台任务。")
public class JobKillTool implements AgentTool {

    private final JobService jobService;

    public JobKillTool(JobService jobService) {
        this.jobService = jobService;
    }

    @Override
    public String name() {
        return "job_kill";
    }

    @Override
    public ToolSchema getSchema() {
        return ToolSchema.builder()
                .name(name())
                .description("终止后台任务。")
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
        if (!jobService.kill(context.sessionId(), jobId)) {
            return ToolResult.failure("任务不存在: " + jobId);
        }
        return ToolResult.success("任务已终止: " + jobId);
    }
}
