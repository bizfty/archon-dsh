package com.bizfty.anchon.dsh.fs;

import com.bizfty.anchon.dsh.tool.AgentTool;
import com.bizfty.anchon.dsh.tool.Tool;
import com.bizfty.anchon.dsh.tool.ToolCall;
import com.bizfty.anchon.dsh.tool.ToolContext;
import com.bizfty.anchon.dsh.tool.ToolResult;
import com.bizfty.anchon.dsh.tool.ToolSchema;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;

/**
 * grep 工具 — 行内正则搜索（对应 DSH fs/tool-fs-search 的 grep；Java 侧自实现，无 ripgrep 依赖）。
 */
@Tool(name = "grep", description = "在文件树中按正则搜索内容，返回 文件→匹配行 分组。")
public class GrepTool implements AgentTool {

    private static final int MAX_RESULTS = 200;

    @Override
    public String name() {
        return "grep";
    }

    @Override
    public ToolSchema getSchema() {
        return ToolSchema.builder()
                .name(name())
                .description("按正则搜索文件内容。")
                .addParameter("pattern", "string", "正则表达式")
                .addParameter("path", "string", "起始目录（默认工作区）")
                .addParameter("include", "string", "文件 glob 过滤（如 *.java），可选")
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
        String patternText = call.getString("pattern");
        if (patternText == null || patternText.isBlank()) {
            return ToolResult.failure("缺少必要参数 pattern");
        }
        Pattern pattern;
        try {
            pattern = Pattern.compile(patternText);
        } catch (PatternSyntaxException e) {
            return ToolResult.failure("正则非法: " + e.getMessage());
        }
        Path base = FsPathPolicy.normalize(call.getString("path", ""), context.cwd());
        if (!Files.isDirectory(base)) {
            return ToolResult.failure("目录不存在: " + base);
        }
        String include = call.getString("include", null);
        java.nio.file.PathMatcher matcher = include == null ? null
                : java.nio.file.FileSystems.getDefault().getPathMatcher("glob:" + include);
        Map<String, List<Map<String, Object>>> matches = new LinkedHashMap<>();
        int count = 0;
        try (Stream<Path> walk = Files.walk(base)) {
            java.util.Iterator<Path> it = walk.filter(Files::isRegularFile).iterator();
            while (it.hasNext() && count < MAX_RESULTS) {
                Path file = it.next();
                String rel = base.relativize(file).toString();
                if (matcher != null && !matcher.matches(file.getFileName())) {
                    continue;
                }
                List<Map<String, Object>> lines = new ArrayList<>();
                try (Stream<String> ls = Files.lines(file)) {
                    java.util.Iterator<String> lit = ls.iterator();
                    int lineNo = 0;
                    while (lit.hasNext() && count < MAX_RESULTS) {
                        lineNo++;
                        String line = lit.next();
                        if (pattern.matcher(line).find()) {
                            lines.add(Map.of("line", lineNo, "text", line.length() > 300 ? line.substring(0, 300) + "..." : line));
                            count++;
                        }
                    }
                } catch (IOException ignored) {
                    // 跳过不可读文件
                }
                if (!lines.isEmpty()) {
                    matches.put(rel, lines);
                }
            }
        } catch (IOException e) {
            return ToolResult.failure("grep 失败: " + e.getMessage());
        }
        return ToolResult.success("匹配 " + count + " 行，分布于 " + matches.size() + " 个文件",
                Map.of("matches", matches, "truncated", count >= MAX_RESULTS));
    }
}
