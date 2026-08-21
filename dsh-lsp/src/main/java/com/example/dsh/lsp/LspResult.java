package com.example.dsh.lsp;

/**
 * LSP 查询结果（对应 DSH lsp 的 4 个语义操作输出：goToDefinition/findReferences/
 * goToImplementation/hover）。
 */
public record LspResult(String summary) {

    public static LspResult of(String summary) {
        return new LspResult(summary == null ? "" : summary);
    }
}
