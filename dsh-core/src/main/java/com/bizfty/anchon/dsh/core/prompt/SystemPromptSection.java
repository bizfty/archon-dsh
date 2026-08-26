package com.bizfty.anchon.dsh.core.prompt;

/**
 * system-prompt 段 — 有序贡献一行提示内容。
 * <p>
 * 对应 DSH core/system-prompt 的 section 注册表。order 波段约定：
 * -100 上下文（cwd 等）；0 persona；10+ 技能/计划注入；100+ 工具指导。
 * 返回空串表示本段不贡献内容。
 */
public interface SystemPromptSection {

    /** 段顺序（越小越靠前）。 */
    int order();

    /** 渲染该段；返回空串则不贡献。 */
    String render(SystemPromptContext context);
}
