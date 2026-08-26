package com.bizfty.anchon.dsh.lsp;

import com.bizfty.anchon.dsh.tool.ToolCall;
import com.bizfty.anchon.dsh.tool.ToolContext;
import com.bizfty.anchon.dsh.tool.ToolResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * LSP 缝测试：provider 路由、扩展名匹配、无提供者结构化失败。
 */
class LspToolTest {

    @SuppressWarnings("unchecked")
    private LspRuntime runtimeWith(LspProvider... providers) {
        ObjectProvider<LspProvider> op = mock(ObjectProvider.class);
        when(op.orderedStream()).thenReturn(Stream.of(providers));
        return new LspRuntime(op);
    }

    @Test
    void routesToMatchingProvider() {
        LspProvider javaProvider = new FakeProvider("java", "定义在 Foo.java:10");
        LspRuntime runtime = runtimeWith(javaProvider);
        LspTool tool = new LspTool(runtime);

        ToolResult result = tool.execute(new ToolCall("c1", "lsp",
                java.util.Map.of("operation", "go_to_definition", "file", "/src/A.java",
                        "line", 5, "character", 3)),
                ToolContext.builder().build());

        assertTrue(result.success());
        assertTrue(result.message().contains("Foo.java:10"));
    }

    @Test
    void extensionMismatchFallsThroughToNoResult() {
        LspProvider javaProvider = new FakeProvider("java", "x");
        LspRuntime runtime = runtimeWith(javaProvider);
        LspTool tool = new LspTool(runtime);

        ToolResult result = tool.execute(new ToolCall("c1", "lsp",
                java.util.Map.of("operation", "hover", "file", "/src/main.py", "line", 1, "character", 0)),
                ToolContext.builder().build());

        assertTrue(!result.success());
        assertTrue(result.message().contains("无结果"));
    }

    @Test
    void oneBasedLineIsConverted() {
        FakeProvider capture = new FakeProvider("java", "ok");
        LspRuntime runtime = runtimeWith(capture);
        LspTool tool = new LspTool(runtime);
        ToolResult result = tool.execute(new ToolCall("c1", "lsp",
                java.util.Map.of("operation", "find_references", "file", "A.java", "line", 3, "character", 0)),
                ToolContext.builder().build());
        assertTrue(result.success());
        assertEquals(2, capture.lastLine, "1-based line 3 应转为 0-based 2");
    }

    private static final class FakeProvider implements LspProvider {
        private final String ext;
        private final String summary;
        private int lastLine = -1;

        FakeProvider(String ext, String summary) {
            this.ext = ext;
            this.summary = summary;
        }

        @Override
        public String name() {
            return "fake-" + ext;
        }

        @Override
        public List<String> extensions() {
            return List.of("." + ext);
        }

        @Override
        public Optional<LspResult> goToDefinition(String file, int line, int character) {
            lastLine = line;
            return Optional.of(LspResult.of(summary));
        }

        @Override
        public Optional<LspResult> findReferences(String file, int line, int character) {
            lastLine = line;
            return Optional.of(LspResult.of(summary));
        }

        @Override
        public Optional<LspResult> hover(String file, int line, int character) {
            lastLine = line;
            return Optional.of(LspResult.of(summary));
        }
    }
}
