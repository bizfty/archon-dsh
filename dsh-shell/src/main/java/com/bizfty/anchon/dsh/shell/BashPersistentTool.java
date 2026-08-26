package com.bizfty.anchon.dsh.shell;

import com.bizfty.anchon.dsh.tool.AgentTool;
import com.bizfty.anchon.dsh.tool.Tool;
import com.bizfty.anchon.dsh.tool.ToolCall;
import com.bizfty.anchon.dsh.tool.ToolContext;
import com.bizfty.anchon.dsh.tool.ToolResult;
import com.bizfty.anchon.dsh.tool.ToolSchema;

import java.util.Map;

/**
 * bash_persistent 工具 — 持久 shell：状态（cwd/export/函数）跨调用保留
 * （对应 DSH terminal/tool-terminal 的能力面；无 TTY，交互程序受限）。
 */
@Tool(name = "bash_persistent",
      description = "在持久 shell 中执行命令。状态（工作目录/环境变量/函数）跨调用保留；"
              + "适合需要连续操作同一环境的场景。")
public class BashPersistentTool implements AgentTool {

    private final PersistentShellManager shellManager;

    public BashPersistentTool(PersistentShellManager shellManager) {
        this.shellManager = shellManager;
    }

    @Override
    public String name() {
        return "bash_persistent";
    }

    @Override
    public ToolSchema getSchema() {
        return ToolSchema.builder()
                .name(name())
                .description("持久 shell 命令。")
                .addParameter("command", "string", "要执行的命令")
                .addParameter("timeout_ms", "integer", "超时毫秒（默认 60000）")
                .required("command")
                .build();
    }

    @Override
    public ToolResult execute(ToolCall call, ToolContext context) {
        String command = call.getString("command");
        if (command == null || command.isBlank()) {
            return ToolResult.failure("缺少必要参数 command");
        }
        if (context.sessionId() == null) {
            return ToolResult.failure("持久 shell 需要 sessionId");
        }
        if (context.effectiveSandboxMode() == com.bizfty.anchon.dsh.tool.SandboxMode.READ_ONLY) {
            return ToolResult.failure("只读模式（read-only），禁止执行 shell 命令");
        }
        int timeoutMs = call.getInt("timeout_ms", 60_000);
        PersistentShellManager.ShellResult result =
                shellManager.execute(context.sessionId(), command, timeoutMs);
        return ToolResult.success(result.output(), Map.of(
                "timed_out", result.timedOut(),
                "persistent", true));
    }
}
