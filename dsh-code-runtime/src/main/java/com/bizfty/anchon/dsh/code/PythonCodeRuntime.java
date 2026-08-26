package com.bizfty.anchon.dsh.code;

import com.bizfty.anchon.dsh.tool.ToolExecutionPipeline;
import com.bizfty.anchon.dsh.tool.ToolResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Python 代码运行时（language=python；python3 直接执行，对应 DSH code-runtime 的 Python SDK 面）。
 */
@Component
public class PythonCodeRuntime extends AbstractProcessCodeRuntime {

    private final String pythonPath;

    public PythonCodeRuntime(ToolExecutionPipeline pipeline,
                             @Value("${dsh.code-runtime.python-path:python3}") String pythonPath,
                             @Value("${dsh.code-runtime.timeout-ms:60000}") long defaultTimeoutMs,
                             @Value("${dsh.code-runtime.max-output-bytes:262144}") long maxOutputBytes) {
        super(pipeline, defaultTimeoutMs, maxOutputBytes);
        this.pythonPath = pythonPath;
    }

    @Override
    public String language() {
        return "python";
    }

    @Override
    protected String executable() {
        return pythonPath;
    }

    @Override
    protected String shimResource() {
        return "run_code_shim.py";
    }

    @Override
    protected void writeToolResult(java.io.Writer stdin, Object id, ToolResult result) throws java.io.IOException {
        if (result.success()) {
            writeLine(stdin, jsonUtils.toJson(Map.of("id", id, "result", result.toMap())));
        } else {
            writeLine(stdin, jsonUtils.toJson(Map.of("id", id, "error", result.message())));
        }
    }
}
