package com.bizfty.anchon.dsh.tool;

import com.bizfty.anchon.dsh.util.JsonUtils;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 工具注册表测试：注解驱动注册、重名拒绝、按名查找。
 */
class ToolRegistryTest {

    @Test
    void registersAnnotatedTools() {
        ApplicationContext ctx = mock(ApplicationContext.class);
        EchoTool echoTool = new EchoTool();
        when(ctx.getBeansOfType(AgentTool.class)).thenReturn(Map.of("echoTool", echoTool));

        ToolRegistry registry = new ToolRegistry(ctx);

        assertEquals(1, registry.allTools().size());
        assertTrue(registry.hasTool("echo"));
        assertEquals(echoTool, registry.getTool("echo"));
    }

    @Test
    void duplicateNamesRejected() {
        ApplicationContext ctx = mock(ApplicationContext.class);
        when(ctx.getBeansOfType(AgentTool.class))
                .thenReturn(Map.of("a", new EchoTool(), "b", new AnotherEchoTool()));

        assertThrows(IllegalStateException.class, () -> new ToolRegistry(ctx));
    }

    @Test
    void unknownToolThrows() {
        ApplicationContext ctx = mock(ApplicationContext.class);
        when(ctx.getBeansOfType(AgentTool.class)).thenReturn(Map.of("echoTool", new EchoTool()));
        ToolRegistry registry = new ToolRegistry(ctx);
        assertThrows(IllegalArgumentException.class, () -> registry.getTool("nope"));
    }

    @Test
    void exportsToolRefsWithSchema() {
        ApplicationContext ctx = mock(ApplicationContext.class);
        when(ctx.getBeansOfType(AgentTool.class)).thenReturn(Map.of("echoTool", new EchoTool()));
        ToolRegistry registry = new ToolRegistry(ctx);
        var refs = registry.toToolRefs(new JsonUtils());
        assertEquals(1, refs.size());
        assertEquals("echo", refs.get(0).name());
        assertTrue(refs.get(0).schemaJson().contains("\"text\""));
    }

    @Tool(name = "echo", description = "回显")
    public static class EchoTool implements AgentTool {
        @Override
        public String name() {
            return "echo";
        }

        @Override
        public ToolSchema getSchema() {
            return ToolSchema.builder()
                    .name(name())
                    .description("回显输入")
                    .addParameter("text", "string", "要回显的文本")
                    .required("text")
                    .build();
        }

        @Override
        public ToolResult execute(ToolCall call, ToolContext context) {
            return ToolResult.success("echo: " + call.getString("text"));
        }
    }

    @Tool(name = "echo", description = "重复名")
    public static class AnotherEchoTool implements AgentTool {
        @Override
        public String name() {
            return "echo";
        }

        @Override
        public ToolSchema getSchema() {
            return ToolSchema.builder().name(name()).description("").build();
        }

        @Override
        public ToolResult execute(ToolCall call, ToolContext context) {
            return ToolResult.success("another");
        }
    }
}
