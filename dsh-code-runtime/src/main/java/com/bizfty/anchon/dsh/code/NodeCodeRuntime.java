package com.bizfty.anchon.dsh.code;

import com.bizfty.anchon.dsh.tool.ToolExecutionPipeline;
import com.bizfty.anchon.dsh.tool.ToolResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Node.js 代码运行时（language=js；对应 DSH code-runtime 的 TS/JS SDK 面）。
 */
@Component
public class NodeCodeRuntime extends AbstractProcessCodeRuntime {

    private final String nodePath;

    public NodeCodeRuntime(ToolExecutionPipeline pipeline,
                           @Value("${dsh.code-runtime.node-path:/usr/local/node/bin/node}") String nodePath,
                           @Value("${dsh.code-runtime.timeout-ms:60000}") long defaultTimeoutMs,
                           @Value("${dsh.code-runtime.max-output-bytes:262144}") long maxOutputBytes) {
        super(pipeline, defaultTimeoutMs, maxOutputBytes);
        this.nodePath = nodePath;
    }

    @Override
    public String language() {
        return "js";
    }

    @Override
    protected String executable() {
        return nodePath;
    }

    @Override
    protected String shimResource() {
        return "run_code_shim.js";
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
