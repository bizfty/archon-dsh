package com.bizfty.anchon.dsh.plan;

import com.bizfty.anchon.dsh.tool.AgentTool;
import com.bizfty.anchon.dsh.tool.Tool;
import com.bizfty.anchon.dsh.tool.ToolCall;
import com.bizfty.anchon.dsh.tool.ToolContext;
import com.bizfty.anchon.dsh.tool.ToolResult;
import com.bizfty.anchon.dsh.tool.ToolSchema;

import java.util.List;

/**
 * spec_validate 工具 — 校验行为契约（spec）文本格式（对齐 OpenSpec 契约规则）：
 * Requirement 用 SHALL/MUST、每 Requirement 至少一个 #### Scenario、
 * 增量头仅 ADDED/MODIFIED/REMOVED/RENAMED Requirements 或 Purpose。
 * 供模型在写契约步骤（kind=spec）后自校验，也供人类审阅前检查。
 */
@Tool(name = "spec_validate",
        description = "校验行为契约（spec）文本：Requirement 用 SHALL/MUST、每 Requirement 至少一个 #### Scenario（GIVEN/WHEN/THEN）、增量头仅 ADDED/MODIFIED/REMOVED/RENAMED Requirements。")
public class SpecValidateTool implements AgentTool {

    @Override
    public String name() {
        return "spec_validate";
    }

    @Override
    public ToolSchema getSchema() {
        return ToolSchema.builder()
                .name(name())
                .description("校验行为契约文本格式。")
                .addParameter("text", "string", "契约文本（markdown）")
                .required("text")
                .build();
    }

    @Override
    public ToolResult execute(ToolCall call, ToolContext context) {
        String text = call.getString("text");
        if (text == null || text.isBlank()) {
            return ToolResult.failure("缺少必要参数 text");
        }
        List<String> errors = SpecContractValidator.validate(text);
        if (errors.isEmpty()) {
            return ToolResult.success("契约校验通过（SHALL/MUST + 场景齐全）");
        }
        return ToolResult.failure("契约校验失败:\n" + String.join("\n", errors));
    }
}
