package com.bizfty.anchon.dsh.core.prompt;

import com.bizfty.anchon.dsh.core.model.Agent;
import com.bizfty.anchon.dsh.core.model.Session;
import org.springframework.beans.factory.annotation.Autowired;
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
 * 对应 DSH core/system-prompt。组装 = 内建段（上下文/persona/交互约定）+ 注册段（技能/计划/工具指导），
 * 最后对 {{variable}} 做严格插值：未知变量 fail loud（不静默留空）。
 * 注意：工具 schema 本体不进 prompt 文本 — 它们随模型请求的 tools 声明下发；
 * 本服务只渲染工具指导段（帮助模型理解调用约束）。
 */
@Component
public class SystemPromptService {

    private static final Pattern VARIABLE_PATTERN = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_.-]+)\\s*}}");

    private final List<SystemPromptSection> sections;
    private final PromptTemplateRenderer templateRenderer;

    @Autowired
    public SystemPromptService(List<SystemPromptSection> sections,
                                PromptTemplateRenderer templateRenderer) {
        this.sections = sections.stream()
                .sorted(Comparator.comparingInt(SystemPromptSection::order))
                .toList();
        this.templateRenderer = templateRenderer;
    }

    /** 向后兼容：使用空实现渲染器（仅用于测试）。 */
    public SystemPromptService(List<SystemPromptSection> sections) {
        this(sections, (path, vars) -> "");
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
        sb.append(renderContextSection(context));
        sb.append(renderPersonaSection(context));
        sb.append(renderInteractionConventionsSection());
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

    /** 内建上下文段（order -100）。 */
    private String renderContextSection(SystemPromptContext ctx) {
        Map<String, String> v = ctx.variables();
        StringBuilder sb = new StringBuilder();
        if (v.get("cwd") != null) {
            sb.append("- 工作目录: {{cwd}}\n");
        }
        if (v.get("sessionId") != null) {
            sb.append("- 会话: {{sessionId}}\n");
        }
        if (v.get("model") != null) {
            sb.append("- 模型: {{model}}\n");
        }
        return templateRenderer.render("prompt/context-section.txt",
                Map.of(
                        "cwd", v.getOrDefault("cwd", ""),
                        "sessionId", v.getOrDefault("sessionId", ""),
                        "model", v.getOrDefault("model", "")))
                + "\n";
    }

    /** 内建 persona 段（order 0）。 */
    private String renderPersonaSection(SystemPromptContext ctx) {
        Agent agent = ctx.agent();
        String persona = agent != null && agent.systemPrompt() != null && !agent.systemPrompt().isBlank()
                ? agent.systemPrompt()
                : DEFAULT_PERSONA;
        if (!persona.endsWith("\n")) {
            persona = persona + "\n";
        }
        return templateRenderer.render("prompt/persona-section.txt",
                Map.of("persona_text", persona))
                + "\n";
    }

    /** 内建交互约定段。 */
    private String renderInteractionConventionsSection() {
        return templateRenderer.render("prompt/interaction-conventions.txt", Map.of())
                + "\n";
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