package com.bizfty.anchon.dsh.workflow;

import com.bizfty.anchon.dsh.code.CodeRunResult;
import com.bizfty.anchon.dsh.code.CodeRuntimeService;
import com.bizfty.anchon.dsh.core.model.SessionId;
import com.bizfty.anchon.dsh.tool.ToolCall;
import com.bizfty.anchon.dsh.tool.ToolContext;
import com.bizfty.anchon.dsh.tool.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * workflow 工具测试：脚本执行、结果渲染/截断、失败传播、参数校验。
 */
class WorkflowToolTest {

    private ToolCall call(String... kv) {
        java.util.Map<String, Object> args = new java.util.LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            args.put(kv[i], kv[i + 1]);
        }
        return new ToolCall("call_1", "workflow", args);
    }

    private ToolContext ctx() {
        return ToolContext.builder().sessionId(SessionId.of("s_wf")).build();
    }

    private WorkflowTool tool(CodeRuntimeService runtime, int maxChars) {
        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<CodeRuntimeService> provider =
                mock(org.springframework.beans.factory.ObjectProvider.class);
        when(provider.getObject()).thenReturn(runtime);
        return new WorkflowTool(provider, maxChars);
    }

    @Test
    void runsScriptAndReturnsResult() {
        CodeRuntimeService runtime = mock(CodeRuntimeService.class);
        when(runtime.run(eq("js"), any(String.class), any(ToolContext.class), anyLong()))
                .thenReturn(new CodeRunResult(List.of("log"), Map.of("sum", 42), null));
        WorkflowTool tool = tool(runtime, 50000);

        ToolResult result = tool.execute(call("script", "const r = await tools.subagent({p:'x'}); return {sum:42}"),
                ctx());
        assertTrue(result.success());
        assertTrue(result.message().contains("\"sum\":42"), "应渲染脚本最终值");
    }

    @Test
    void scriptFailurePropagates() {
        CodeRuntimeService runtime = mock(CodeRuntimeService.class);
        when(runtime.run(any(), any(), any(), anyLong()))
                .thenReturn(new CodeRunResult(List.of(), null, "脚本抛错"));
        WorkflowTool tool = tool(runtime, 50000);
        ToolResult result = tool.execute(call("script", "throw new Error('boom')"), ctx());
        assertFalse(result.success());
        assertTrue(result.message().contains("脚本抛错"));
    }

    @Test
    void missingScriptRejected() {
        WorkflowTool tool = tool(mock(CodeRuntimeService.class), 50000);
        ToolResult result = tool.execute(call(), ctx());
        assertFalse(result.success());
        assertTrue(result.message().contains("script"));
    }

    @Test
    void oversizedResultTruncated() {
        CodeRuntimeService runtime = mock(CodeRuntimeService.class);
        when(runtime.run(any(), any(), any(), anyLong()))
                .thenReturn(new CodeRunResult(List.of(), "x".repeat(10_000), null));
        WorkflowTool tool = tool(runtime, 100);
        ToolResult result = tool.execute(call("script", "return 'x'.repeat(10000)"), ctx());
        assertTrue(result.success());
        assertTrue(result.message().length() < 300, "结果应截断");
        assertTrue(result.message().contains("已截断"), "应含截断提示");
    }

    @Test
    void nullResultRendersEmpty() {
        CodeRuntimeService runtime = mock(CodeRuntimeService.class);
        when(runtime.run(any(), any(), any(), anyLong()))
                .thenReturn(new CodeRunResult(List.of(), null, null));
        WorkflowTool tool = tool(runtime, 50000);
        ToolResult result = tool.execute(call("script", "return undefined"), ctx());
        assertTrue(result.success());
        assertEquals("workflow 结果: ", result.message());
    }
}
