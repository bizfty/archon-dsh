package com.bizfty.anchon.dsh.code;

import com.bizfty.anchon.dsh.tool.ToolContext;

/**
 * 代码运行时接口 — 执行模型写的程序（对应 DSH code-runtime）。
 * <p>
 * 实现：Node.js（language=js）、Python（language=python）。
 */
public interface CodeRuntime {

    /** 语言标识（js / python）。 */
    String language();

    /**
     * 执行一段模型写的程序。
     *
     * @param code      程序体（await tools.xxx(args)；return 结果）
     * @param context   工具执行上下文
     * @param timeoutMs 超时毫秒（<=0 用默认）
     */
    CodeRunResult run(String code, ToolContext context, long timeoutMs);
}
