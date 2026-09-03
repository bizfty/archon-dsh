package com.bizfty.anchon.dsh.api;

import com.bizfty.anchon.dsh.tool.AgentTool;
import com.bizfty.anchon.dsh.tool.Tool;
import com.bizfty.anchon.dsh.tool.ToolCall;
import com.bizfty.anchon.dsh.tool.ToolContext;
import com.bizfty.anchon.dsh.tool.ToolRegistry;
import com.bizfty.anchon.dsh.tool.ToolResult;
import com.bizfty.anchon.dsh.tool.ToolSchema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 工具元数据端点测试：投影形状（含/不含显示元数据）、空 registry。
 */
class ToolsMetaControllerTest {

    private ToolRegistry registryOf(AgentTool... tools) {
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.allTools()).thenReturn(List.of(tools));
        return registry;
    }

    @Test
    void metaProjectsDisplayTitleSummaryKeysAndInputSchema() {
        var tool = new DemoAnnotatedTool();
        var controller = new ToolsMetaController(registryOf(tool));

        List<ToolsMetaController.ToolMetaDto> metas = controller.meta();
        assertEquals(1, metas.size());
        ToolsMetaController.ToolMetaDto m = metas.get(0);

        assertEquals("demo_tool", m.name());
        assertEquals("Demo", m.displayTitle());
        assertArrayEquals(new String[]{"path", "query"}, m.summaryKeys());
        assertEquals("演示工具。", m.description());
        assertEquals("object", m.inputSchema().get("type"));
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) m.inputSchema().get("properties");
        assertTrue(props.containsKey("path"));
        assertTrue(props.containsKey("query"));
        assertEquals(List.of("path"), m.inputSchema().get("required"));
        assertTrue(m.requiresApproval());
        assertEquals(42L, m.timeoutMs());
    }

    @Test
    void metaDefaultsToNullTitleAndEmptyKeysWhenNotDeclared() {
        var tool = new DemoPlainTool();
        var controller = new ToolsMetaController(registryOf(tool));

        ToolsMetaController.ToolMetaDto m = controller.meta().get(0);
        assertEquals("plain_tool", m.name());
        assertNull(m.displayTitle(), "未声明 displayTitle → null（前端兜底）");
        assertNotNull(m.summaryKeys());
        assertEquals(0, m.summaryKeys().length, "未声明 summaryKeys → []（前端启发兜底）");
    }

    @Test
    void metaEmptyRegistryReturnsEmptyList() {
        var controller = new ToolsMetaController(registryOf());
        assertTrue(controller.meta().isEmpty());
    }

    // ---- stub 工具 ----

    @Tool(name = "demo_tool", description = "演示工具。", displayTitle = "Demo",
            summaryKeys = {"path", "query"}, requiresApproval = true, timeoutMs = 42)
    static class DemoAnnotatedTool implements AgentTool {
        @Override
        public String name() {
            return "demo_tool";
        }

        @Override
        public ToolSchema getSchema() {
            return ToolSchema.builder().name(name()).description("演示工具。")
                    .addParameter("path", "string", "路径")
                    .addParameter("query", "string", "查询")
                    .required("path")
                    .build();
        }

        @Override
        public ToolResult execute(ToolCall call, ToolContext context) {
            return ToolResult.success("ok");
        }
    }

    @Tool(name = "plain_tool", description = "无显示元数据。")
    static class DemoPlainTool implements AgentTool {
        @Override
        public String name() {
            return "plain_tool";
        }

        @Override
        public ToolSchema getSchema() {
            return ToolSchema.builder().name(name()).description("无显示元数据。")
                    .addParameter("x", "string", "x")
                    .build();
        }

        @Override
        public ToolResult execute(ToolCall call, ToolContext context) {
            return ToolResult.success("ok");
        }
    }
}
