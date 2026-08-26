package com.bizfty.anchon.dsh.core.prompt;

import java.util.Map;

/**
 * Prompt 模板渲染器 — 从 classpath 加载模板文件并进行变量替换。
 * <p>
 * 默认实现使用 {@code ${var}} 占位符；各模块可按需引入 Pebble/FreeMarker 等
 * 重型模板引擎并通过 Spring Bean 替换本接口实现。
 */
public interface PromptTemplateRenderer {

    /**
     * 渲染模板。
     *
     * @param templatePath classpath 相对路径，如 {@code "prompt/tools-guidance.txt"}
     * @param variables    变量名 → 值（值通过 {@link Object#toString()} 转换为字符串）
     * @return 渲染后的文本
     */
    String render(String templatePath, Map<String, ?> variables);
}