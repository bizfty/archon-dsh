package com.example.dsh.core.prompt;

import com.example.dsh.core.model.Agent;
import com.example.dsh.core.model.Session;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * system-prompt 组装服务 — 有序段 + 严格变量插值。
 * <p>
 * 对应 DSH core/system-prompt。组装 = 内建段（上下文/persona）+ 注册段（技能/计划/工具指导），
 * 最后对 {{variable}} 做严格插值：未知变量 fail loud（不静默留空）。
 * 注意：工具 schema 本体不进 prompt 文本 — 它们随模型请求的 tools 声明下发；
 * 本服务只渲染工具指导段（帮助模型理解调用约束）。
 */
@Component
public class SystemPromptService {

    private static final Pattern VARIABLE_PATTERN = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_.-]+)\\s*}}");

    private final List<SystemPromptSection> sections;

    public SystemPromptService(List<SystemPromptSection> sections) {
        this.sections = sections.stream()
                .sorted(Comparator.comparingInt(SystemPromptSection::order))
                .toList();
    }

    /**
     * 组装完整 system prompt。
     *
     * @param context 组装上下文（session/agent/tools/变量）
     */
    public String assemble(SystemPromptContext context) {
        return assemble(context, List.of());
    }

    /**
     * 组装完整 system prompt（带作用域额外段）。
     *
     * @param context       组装上下文
     * @param extraSections 作用域级额外段（AgentScope），与注册段按 order 合并
     */
    public String assemble(SystemPromptContext context, List<SystemPromptSection> extraSections) {
        List<SystemPromptSection> all = java.util.stream.Stream.concat(
                        this.sections.stream(),
                        extraSections == null ? java.util.stream.Stream.empty() : extraSections.stream())
                .sorted(Comparator.comparingInt(SystemPromptSection::order))
                .toList();
        StringBuilder sb = new StringBuilder();
        renderContextSection(sb, context);
        renderPersonaSection(sb, context);
        renderToolGuidanceSection(sb);
        for (SystemPromptSection section : all) {
            String rendered;
            try {
                rendered = section.render(context);
            } catch (RuntimeException e) {
                throw new PromptAssemblyException(
                        "system-prompt 段渲染失败: " + section.getClass().getSimpleName() + " — " + e.getMessage(), e);
            }
            if (rendered != null && !rendered.isBlank()) {
                sb.append(rendered);
                if (!rendered.endsWith("\n")) {
                    sb.append('\n');
                }
            }
        }
        return interpolate(sb.toString(), context.variables());
    }

    /** 内建上下文段：cwd / 会话 id / 模型（order -100）。 */
    private void renderContextSection(StringBuilder sb, SystemPromptContext ctx) {
        Map<String, String> v = ctx.variables();
        sb.append("## 会话上下文\n");
        if (v.get("cwd") != null) {
            sb.append("- 工作目录: {{cwd}}\n");
        }
        if (v.get("sessionId") != null) {
            sb.append("- 会话: {{sessionId}}\n");
        }
        if (v.get("model") != null) {
            sb.append("- 模型: {{model}}\n");
        }
        sb.append('\n');
    }

    /** 内建 persona 段（order 0）：agent.systemPrompt 或部署默认。 */
    private void renderPersonaSection(StringBuilder sb, SystemPromptContext ctx) {
        Agent agent = ctx.agent();
        String persona = agent != null && agent.systemPrompt() != null && !agent.systemPrompt().isBlank()
                ? agent.systemPrompt()
                : DEFAULT_PERSONA;
        sb.append(persona);
        if (!persona.endsWith("\n")) {
            sb.append('\n');
        }
        sb.append('\n');
    }

    /**
     * 内建工具指导段：与用户交互的约定（对应 DSH system-prompt 的工具指导面）。
     * 无论 agent 是否自定义 persona 都出现。
     */
    private void renderToolGuidanceSection(StringBuilder sb) {
        sb.append("## 交互约定\n");
        sb.append("- 当需要用户做选择、确认偏好或提供决定时，使用 ask_user_question 工具提供选项并等待回答，");
        sb.append("不要在回答文本里直接罗列选项让用户回复。\n");
        sb.append("- 面向用户的选择应作为可点选选项（单选/多选），而非让用户手打文字。\n");
        sb.append('\n');
    }

    /** 严格变量插值：{{name}} → 变量值；未知变量抛异常。 */
    private String interpolate(String text, Map<String, String> variables) {
        if (text == null || text.indexOf("{{") < 0) {
            return text;
        }
        Matcher matcher = VARIABLE_PATTERN.matcher(text);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            String name = matcher.group(1);
            String value = variables == null ? null : variables.get(name);
            if (value == null) {
                throw new PromptAssemblyException("未知 prompt 变量: " + name);
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /** 部署默认 persona。 */
    public static final String DEFAULT_PERSONA = """
            你是 Archon，一位全域业务统筹管家。
            - 面对复杂任务，先规划再执行。
            - 需要工具时直接调用；工具结果失败时基于错误信息自纠正。
            - 使用与用户相同的语言回答。
            """;

    /** 组装失败（未知变量 / 段渲染异常）。 */
    public static final class PromptAssemblyException extends RuntimeException {
        public PromptAssemblyException(String message) {
            super(message);
        }

        public PromptAssemblyException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** 构造 context 时预填的内建变量。 */
    public static Map<String, String> baseVariables(Session session, Agent agent) {
        Map<String, String> vars = new LinkedHashMap<>();
        if (session != null) {
            vars.put("sessionId", session.id().value());
            if (session.title() != null) {
                vars.put("title", session.title());
            }
            if (session.cwd() != null) {
                vars.put("cwd", session.cwd());
            }
        }
        if (agent != null) {
            if (agent.model() != null) {
                vars.put("model", agent.model());
            }
            if (agent.provider() != null) {
                vars.put("provider", agent.provider());
            }
            if (agent.cwd() != null) {
                vars.put("cwd", agent.cwd());
            }
        }
        return vars;
    }
}
