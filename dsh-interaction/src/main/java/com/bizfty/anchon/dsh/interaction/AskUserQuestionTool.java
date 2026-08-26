package com.bizfty.anchon.dsh.interaction;

import com.bizfty.anchon.dsh.core.event.SessionEvent;
import com.bizfty.anchon.dsh.core.event.SessionEventBus;
import com.bizfty.anchon.dsh.core.event.SessionEventType;
import com.bizfty.anchon.dsh.tool.AgentTool;
import com.bizfty.anchon.dsh.tool.Tool;
import com.bizfty.anchon.dsh.tool.ToolCall;
import com.bizfty.anchon.dsh.tool.ToolContext;
import com.bizfty.anchon.dsh.tool.ToolResult;
import com.bizfty.anchon.dsh.tool.ToolSchema;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ask_user_question 工具 — 向用户提问并等待应答（对应 DSH interaction/tool-ask-user）。
 * <p>
 * 阻塞前发布 {@link SessionEventType#QUESTION_REQUESTED} 事件（含问题与选项），
 * SSE 流据此推送 question 事件，前端渲染选择框；用户应答后工具继续。
 * 无应答者/超时时返回结构化失败，模型据此继续或调整。
 */
@Tool(name = "ask_user_question",
      description = "向用户提出一个问题并等待回答（可带选项与多选）。")
public class AskUserQuestionTool implements AgentTool {

    private final UserQuestionService questionService;
    private final SessionEventBus eventBus;

    public AskUserQuestionTool(UserQuestionService questionService, SessionEventBus eventBus) {
        this.questionService = questionService;
        this.eventBus = eventBus;
    }

    @Override
    public String name() {
        return "ask_user_question";
    }

    @Override
    public ToolSchema getSchema() {
        return ToolSchema.builder()
                .name(name())
                .description("向用户提问。")
                .addParameter("question", "string", "问题内容")
                .addArrayParameter("options", "选项列表（可选）", "string")
                .addParameter("multi_select", "boolean", "是否多选（默认 false）")
                .required("question")
                .build();
    }

    @Override
    public ToolResult execute(ToolCall call, ToolContext context) {
        String question = call.getString("question");
        if (question == null || question.isBlank()) {
            return ToolResult.failure("缺少必要参数 question");
        }
        List<String> options = call.getStringList("options");
        boolean multiSelect = call.getBool("multi_select", false);
        try {
            // 阻塞前发布事件：SSE 推送 question → 前端渲染选择框
            publishQuestion(context, question, options == null ? List.of() : options, multiSelect);
            String answer = questionService.ask(question, options == null ? List.of() : options, multiSelect);
            return ToolResult.success("用户已回答: " + answer, Map.of("answer", answer));
        } catch (UserQuestionService.NoAnswerProviderException e) {
            return ToolResult.failure("无法向用户提问: " + e.getMessage()
                    + " — 请基于已有信息继续，或说明需要用户提供的信息。");
        }
    }

    private void publishQuestion(ToolContext context, String question, List<String> options, boolean multiSelect) {
        if (eventBus == null || context.sessionId() == null) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("question", question);
        payload.put("options", options);
        payload.put("multiSelect", multiSelect);
        eventBus.publish(context.sessionId(), SessionEventType.QUESTION_REQUESTED, payload);
    }
}
