package com.bizfty.anchon.dsh.fs;

import com.bizfty.anchon.dsh.tool.AgentTool;
import com.bizfty.anchon.dsh.tool.Tool;
import com.bizfty.anchon.dsh.tool.ToolCall;
import com.bizfty.anchon.dsh.tool.ToolContext;
import com.bizfty.anchon.dsh.tool.ToolResult;
import com.bizfty.anchon.dsh.tool.ToolSchema;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;

/**
 * write_file 工具 — 写文件（仅限工作区内；对应 DSH fs/tool-fs 的 write）。
 */
@Tool(name = "write_file", description = "写入文件内容（覆盖写，路径必须在工作区内）。")
public class WriteFileTool implements AgentTool {

    @Override
    public String name() {
        return "write_file";
    }

    @Override
    public ToolSchema getSchema() {
        return ToolSchema.builder()
                .name(name())
                .description("写入/覆盖文件。")
                .addParameter("path", "string", "文件路径（相对工作区）")
                .addParameter("content", "string", "完整文件内容")
                .required("path", "content")
                .build();
    }

    @Override
    public ToolResult execute(ToolCall call, ToolContext context) {
        String rawPath = call.getString("path");
        String content = call.getString("content", "");
        if (rawPath == null || rawPath.isBlank()) {
            return ToolResult.failure("缺少必要参数 path");
        }
        Path path = FsPathPolicy.normalize(rawPath, context.cwd());
        String denial = FsPathPolicy.checkWritable(path, context.cwd(), context.effectiveSandboxMode());
        if (denial != null) {
            return ToolResult.failure(denial);
        }
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(path, content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            return ToolResult.success("已写入 " + path + " (" + content.length() + " 字符)",
                    Map.of("path", path.toString(), "bytes", content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length));
        } catch (Exception e) {
            return ToolResult.failure("写入失败: " + e.getMessage());
        }
    }
}
