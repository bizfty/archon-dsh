package com.bizfty.anchon.dsh.shell;

import com.bizfty.anchon.dsh.jobs.JobHandle;
import com.bizfty.anchon.dsh.jobs.JobService;
import com.bizfty.anchon.dsh.tool.AgentTool;
import com.bizfty.anchon.dsh.tool.Tool;
import com.bizfty.anchon.dsh.tool.ToolCall;
import com.bizfty.anchon.dsh.tool.ToolContext;
import com.bizfty.anchon.dsh.tool.ToolResult;
import com.bizfty.anchon.dsh.tool.ToolSchema;

import java.util.Map;

/**
 * bash 工具 — 前台或后台执行 shell 命令（对应 DSH shell/tool-bash 的前台 + run_in_background）。
 * <p>
 * 前台：每次全新进程，无跨调用状态；后台：经 JobService 启动，返回 job_id 供 job_status 查询。
 */
@Tool(name = "bash", description = "执行 shell 命令。前台（默认）或后台（run_in_background=true，返回 job_id 后用 job_status 查询）。")
public class BashTool implements AgentTool {

    private final BashExecutor bashExecutor;
    private final JobService jobService;

    public BashTool(BashExecutor bashExecutor, JobService jobService) {
        this.bashExecutor = bashExecutor;
        this.jobService = jobService;
    }

    @Override
    public String name() {
        return "bash";
    }

    @Override
    public ToolSchema getSchema() {
        return ToolSchema.builder()
                .name(name())
                .description("在 shell 中执行命令。")
                .addParameter("command", "string", "要执行的命令")
                .addParameter("workdir", "string", "工作目录（默认会话工作区）")
                .addParameter("timeout_ms", "integer", "前台超时毫秒（默认 60000）")
                .addParameter("run_in_background", "boolean", "后台运行（默认 false；true 时返回 job_id）")
                .required("command")
                .build();
    }

    @Override
    public ToolResult execute(ToolCall call, ToolContext context) {
        String command = call.getString("command");
        if (command == null || command.isBlank()) {
            return ToolResult.failure("缺少必要参数 command");
        }
        // 沙箱门控：只读模式禁止执行命令（策略栅栏，对应 DSH sandbox 对 shell 的栅栏）
        if (context.effectiveSandboxMode() == com.bizfty.anchon.dsh.tool.SandboxMode.READ_ONLY) {
            return ToolResult.failure("只读模式（read-only），禁止执行 shell 命令");
        }
        String workdir = call.getString("workdir", context.cwd());
        boolean background = call.getBool("run_in_background", false);

        if (background) {
            if (context.sessionId() == null) {
                return ToolResult.failure("后台任务需要 sessionId");
            }
            JobHandle job = jobService.start("bash", command, workdir, context.sessionId(),
                    managedEnv(context, workdir));
            return ToolResult.success("后台任务已启动: " + job.id() + "（用 job_status 查询状态与输出）",
                    Map.of("job_id", job.id(), "background", true));
        }

        int timeoutMs = call.getInt("timeout_ms", 60_000);
        BashExecutor.BashResult result = bashExecutor.run(command, workdir, timeoutMs,
                managedEnv(context, workdir));
        return ToolResult.success(result.toDisplayText(), Map.of(
                "exit_code", result.exitCode(),
                "timed_out", result.timedOut(),
                "elapsed_ms", result.elapsedMs()));
    }

    /** 受管环境变量（对应 DSH shell-env）：DSH_SESSION_ID / DSH_WORKDIR。 */
    private Map<String, String> managedEnv(ToolContext context, String workdir) {
        Map<String, String> env = new java.util.LinkedHashMap<>();
        if (context.sessionId() != null) {
            env.put("DSH_SESSION_ID", context.sessionId().value());
        }
        env.put("DSH_WORKDIR", workdir == null ? "" : workdir);
        return env;
    }
}
