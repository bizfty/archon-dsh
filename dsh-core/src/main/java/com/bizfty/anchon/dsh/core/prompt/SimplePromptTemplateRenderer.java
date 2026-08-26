package com.bizfty.anchon.dsh.core.prompt;

import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 默认模板渲染实现 — 使用 {@code ${var}} 占位符语法。
 * <p>
 * 各模块通过在 classpath 下放置 {@code prompt/*.txt} 文件来管理提示词内容。
 * 如需更复杂的模板逻辑（循环、条件等），可在子模块中引入 Pebble/FreeMarker
 * 并注册新的 {@link PromptTemplateRenderer} Bean 覆盖本实现。
 */
@Component
public class SimplePromptTemplateRenderer implements PromptTemplateRenderer {

    private final ResourceLoader resourceLoader;
    private final Map<String, String> templateCache = new ConcurrentHashMap<>();

    public SimplePromptTemplateRenderer(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }

    @Override
    public String render(String templatePath, Map<String, ?> variables) {
        String template = templateCache.computeIfAbsent(templatePath, this::loadTemplate);
        String result = template;
        if (variables != null) {
            for (Map.Entry<String, ?> entry : variables.entrySet()) {
                String placeholder = "${" + entry.getKey() + "}";
                String value = entry.getValue() != null ? entry.getValue().toString() : "";
                result = result.replace(placeholder, value);
            }
        }
        return result;
    }

    private String loadTemplate(String path) {
        Resource resource = resourceLoader.getResource("classpath:" + path);
        try {
            return new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("加载 prompt 模板失败: " + path, e);
        }
    }
}