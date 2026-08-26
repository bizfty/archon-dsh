package com.bizfty.anchon.dsh.code;

import com.bizfty.anchon.dsh.tool.ToolContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 代码运行时路由 — 按语言选择运行时（对应 DSH codeRuntime 的 language 选择面）。
 * <p>
 * 已注册：js（NodeCodeRuntime，Node.js）、python（PythonCodeRuntime，python3）。
 */
@Service
public class CodeRuntimeService {

    private final List<CodeRuntime> runtimes;

    public CodeRuntimeService(ObjectProvider<CodeRuntime> runtimeProvider) {
        this.runtimes = runtimeProvider.orderedStream().toList();
    }

    /**
     * 执行一段模型写的程序。
     *
     * @param language js（默认）| python
     */
    public CodeRunResult run(String language, String code, ToolContext context, long timeoutMs) {
        return runtimeFor(language).run(code, context, timeoutMs);
    }

    /** 按语言选择运行时；未知语言 fail loud。 */
    public CodeRuntime runtimeFor(String language) {
        String lang = (language == null || language.isBlank()) ? "js" : language.trim().toLowerCase();
        for (CodeRuntime runtime : runtimes) {
            if (runtime.language().equals(lang)) {
                return runtime;
            }
        }
        throw new IllegalArgumentException("未知 run_code 语言: " + language + "（支持: js/python）");
    }
}
