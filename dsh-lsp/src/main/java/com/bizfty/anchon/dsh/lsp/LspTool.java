package com.bizfty.anchon.dsh.lsp;

import com.bizfty.anchon.dsh.tool.AgentTool;
import com.bizfty.anchon.dsh.tool.Tool;
import com.bizfty.anchon.dsh.tool.ToolCall;
import com.bizfty.anchon.dsh.tool.ToolContext;
import com.bizfty.anchon.dsh.tool.ToolResult;
import com.bizfty.anchon.dsh.tool.ToolSchema;

import java.util.Optional;

/**
 * lsp 工具 — 语义操作（对应 DSH lsp/tool-lsp）。
 * <p>
 * 无匹配提供者时结构化失败（fail loud，schema 稳定）。
 */
@Tool(name = "lsp", description = "语言服务器语义操作：go_to_definition / find_references / go_to_implementation / hover。"
        + "坐标 1-based（line 从 1 开始，character 从 0 开始，UTF-16）。")
public class LspTool implements AgentTool {

    private final LspRuntime lspRuntime;

    public LspTool(LspRuntime lspRuntime) {
        this.lspRuntime = lspRuntime;
    }

    @Override
    public String name() {
        return "lsp";
    }

    @Override
    public ToolSchema getSchema() {
        return ToolSchema.builder()
                .name(name())
                .description("语言服务器查询。")
                .addParameter("operation", "string", "go_to_definition | find_references | go_to_implementation | hover")
                .addParameter("file", "string", "文件路径")
                .addParameter("line", "integer", "行号（1-based）")
                .addParameter("character", "integer", "列号（0-based，UTF-16）")
                .required("operation", "file", "line", "character")
                .build();
    }

    @Override
    public ToolResult execute(ToolCall call, ToolContext context) {
        String operation = call.getString("operation");
        String file = call.getString("file");
        Integer line = call.getInt("line", null);
        Integer character = call.getInt("character", 0);
        if (operation == null || file == null || line == null) {
            return ToolResult.failure("缺少必要参数 operation/file/line");
        }
        int zeroBasedLine = Math.max(0, line - 1);
        Optional<LspResult> result = switch (operation) {
            case "go_to_definition" -> lspRuntime.goToDefinition(file, zeroBasedLine, character);
            case "find_references" -> lspRuntime.findReferences(file, zeroBasedLine, character);
            case "go_to_implementation" -> lspRuntime.goToDefinition(file, zeroBasedLine, character);
            case "hover" -> lspRuntime.hover(file, zeroBasedLine, character);
            default -> Optional.empty();
        };
        if (result.isEmpty()) {
            return ToolResult.failure("LSP 查询无结果（无匹配提供者或符号未找到）: " + operation
                    + " @" + file + ":" + line);
        }
        return ToolResult.success(result.get().summary());
    }
}
