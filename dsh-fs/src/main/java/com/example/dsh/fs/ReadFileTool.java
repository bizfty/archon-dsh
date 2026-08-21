package com.example.dsh.fs;

import com.example.dsh.tool.AgentTool;
import com.example.dsh.tool.Tool;
import com.example.dsh.tool.ToolCall;
import com.example.dsh.tool.ToolContext;
import com.example.dsh.tool.ToolResult;
import com.example.dsh.tool.ToolSchema;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * read_file 工具 — 窗口化读取（对应 DSH fs/tool-fs 的 read）。
 * <p>
 * 上限：offset 从 1 开始，limit 默认 2000 行，单文件 51200 字节。
 */
@Tool(name = "read_file", description = "读取文本文件（窗口化：offset 从 1 开始，limit 默认 2000 行）。")
public class ReadFileTool implements AgentTool {

    private static final long MAX_BYTES = 51_200;
    private static final int DEFAULT_LIMIT = 2000;

    @Override
    public String name() {
        return "read_file";
    }

    @Override
    public ToolSchema getSchema() {
        return ToolSchema.builder()
                .name(name())
                .description("读取文件内容。")
                .addParameter("path", "string", "文件路径（相对工作区或绝对路径）")
                .addParameter("offset", "integer", "起始行号（1 基），默认 1")
                .addParameter("limit", "integer", "读取行数上限，默认 2000")
                .required("path")
                .build();
    }

    @Override
    public ToolResult execute(ToolCall call, ToolContext context) {
        String rawPath = call.getString("path");
        if (rawPath == null || rawPath.isBlank()) {
            return ToolResult.failure("缺少必要参数 path");
        }
        int offset = call.getInt("offset", 1);
        int limit = call.getInt("limit", DEFAULT_LIMIT);
        Path path = FsPathPolicy.normalize(rawPath, context.cwd());
        if (!Files.exists(path)) {
            return ToolResult.failure("文件不存在: " + path);
        }
        if (Files.isDirectory(path)) {
            return ToolResult.failure("是目录，不是文件: " + path);
        }
        try {
            String text = FsPathPolicy.readText(path, MAX_BYTES);
            List<String> lines = text.lines().toList();
            int start = Math.max(0, offset - 1);
            if (start >= lines.size()) {
                return ToolResult.success("文件共 " + lines.size() + " 行，起始行超出范围",
                        Map.of("path", path.toString(), "total_lines", lines.size()));
            }
            int end = Math.min(lines.size(), start + Math.max(1, limit));
            StringBuilder sb = new StringBuilder();
            for (int i = start; i < end; i++) {
                sb.append(String.format("%6d\t%s%n", i + 1, lines.get(i)));
            }
            boolean truncated = end < lines.size();
            return ToolResult.success(sb.toString(), Map.of(
                    "path", path.toString(),
                    "offset", start + 1,
                    "lines", end - start,
                    "total_lines", lines.size(),
                    "truncated", truncated));
        } catch (Exception e) {
            return ToolResult.failure("读取失败: " + e.getMessage());
        }
    }
}
