package com.bizfty.anchon.dsh.fs;

import com.bizfty.anchon.dsh.tool.AgentTool;
import com.bizfty.anchon.dsh.tool.Tool;
import com.bizfty.anchon.dsh.tool.ToolCall;
import com.bizfty.anchon.dsh.tool.ToolContext;
import com.bizfty.anchon.dsh.tool.ToolResult;
import com.bizfty.anchon.dsh.tool.ToolSchema;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * glob 工具 — 路径发现（对应 DSH fs/tool-fs-search 的 glob）。
 * <p>
 * pattern 使用 ant-style 双星模式（形如 "** / *.java" 的递归匹配），相对于工作区递归搜索。
 */
@Tool(name = "glob", description = "按 glob 模式查找文件（如 **/*.java）。")
public class GlobTool implements AgentTool {

    private static final int MAX_RESULTS = 500;

    @Override
    public String name() {
        return "glob";
    }

    @Override
    public ToolSchema getSchema() {
        return ToolSchema.builder()
                .name(name())
                .description("按 glob 模式查找文件。")
                .addParameter("pattern", "string", "glob 模式（如 **/*.java）")
                .addParameter("path", "string", "起始目录（默认工作区）")
                .required("pattern")
                .build();
    }

    /** 只读操作，无共享可变状态 — 并发安全（可并行执行）。 */
    @Override
    public boolean isConcurrencySafe() {
        return true;
    }

    @Override
    public ToolResult execute(ToolCall call, ToolContext context) {
        String pattern = call.getString("pattern");
        if (pattern == null || pattern.isBlank()) {
            return ToolResult.failure("缺少必要参数 pattern");
        }
        Path base = FsPathPolicy.normalize(call.getString("path", ""), context.cwd());
        if (!Files.isDirectory(base)) {
            return ToolResult.failure("目录不存在: " + base);
        }
        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
        List<String> results = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(base)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> matcher.matches(base.relativize(p)))
                    .limit(MAX_RESULTS)
                    .forEach(p -> results.add(base.relativize(p).toString()));
        } catch (IOException e) {
            return ToolResult.failure("glob 失败: " + e.getMessage());
        }
        boolean truncated = results.size() >= MAX_RESULTS;
        return ToolResult.success("匹配 " + results.size() + " 个文件",
                Map.of("files", results, "truncated", truncated));
    }
}
