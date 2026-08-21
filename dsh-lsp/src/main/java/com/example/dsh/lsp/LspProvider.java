package com.example.dsh.lsp;

import java.util.Optional;

/**
 * 语言服务器提供者 SPI（对应 DSH lsp 的 provider 注册：能力而非工具）。
 * <p>
 * 仅 4 个语义操作；LSP4J stdio 后端因依赖不在本地仓库标 P2，当前可注册
 * 自定义提供者（内存/HTTP/远程）。
 */
public interface LspProvider {

    String name();

    /** 支持的文件扩展名（含点，如 ".java"；空 = 全部）。 */
    java.util.List<String> extensions();

    Optional<LspResult> goToDefinition(String file, int line, int character);

    Optional<LspResult> findReferences(String file, int line, int character);

    Optional<LspResult> hover(String file, int line, int character);
}
