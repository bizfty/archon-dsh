package com.bizfty.anchon.dsh.api;

import com.bizfty.anchon.dsh.tool.AgentTool;
import com.bizfty.anchon.dsh.tool.ToolRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 工具元数据端点 — schema 单源消费（前端工具行渲染不再硬编码标题/摘要键）。
 * <p>
 * {@code GET /api/tools/meta} 下发全部注册工具（ToolRegistry）的显示元数据与参数 schema：
 * name / displayTitle（未声明 null）/ summaryKeys（未声明 []）/ description / inputSchema
 * （与注入 LLM 的 function-calling 声明同源同形）/ requiresApproval / timeoutMs。
 * 新工具只要声明 @Tool 注解（可选 displayTitle/summaryKeys），前端即可零改动渲染。
 */
@RestController
@RequestMapping("/api/tools")
public class ToolsMetaController {

    private final ToolRegistry toolRegistry;

    public ToolsMetaController(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    @GetMapping("/meta")
    public List<ToolMetaDto> meta() {
        return toolRegistry.allTools().stream().map(this::toDto).toList();
    }

    private ToolMetaDto toDto(AgentTool tool) {
        var schema = tool.getSchema();
        return new ToolMetaDto(
                tool.name(),
                tool.displayTitle(),
                tool.summaryKeys(),
                schema.description(),
                schema.inputSchema(),
                tool.requiresApproval(),
                tool.timeoutMs());
    }

    /** 工具显示元数据（Schema 单源下发给前端）。 */
    public record ToolMetaDto(
            String name,
            String displayTitle,
            String[] summaryKeys,
            String description,
            Map<String, Object> inputSchema,
            boolean requiresApproval,
            long timeoutMs) {
    }
}
