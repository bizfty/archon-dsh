package com.bizfty.anchon.dsh.context;

import com.bizfty.anchon.dsh.core.prompt.PromptTemplateRenderer;
import com.bizfty.anchon.dsh.core.prompt.SystemPromptContext;
import com.bizfty.anchon.dsh.core.prompt.SystemPromptSection;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 时间上下文段（order -50）— 每次组装注入当前时间。
 */
@Component
public class TimeContextSection implements SystemPromptSection {

    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z");

    private final PromptTemplateRenderer templateRenderer;
    private final Supplier<String> nowText;

    @Autowired
    public TimeContextSection(PromptTemplateRenderer templateRenderer) {
        this.templateRenderer = templateRenderer;
        this.nowText = () -> FORMATTER.format(Clock.systemDefaultZone().instant().atZone(ZoneId.systemDefault()));
    }

    /** 测试用：固定时间。 */
    public TimeContextSection(PromptTemplateRenderer templateRenderer, String fixedTime) {
        this.templateRenderer = templateRenderer;
        this.nowText = () -> fixedTime;
    }

    @Override
    public int order() {
        return -50;
    }

    @Override
    public String render(SystemPromptContext context) {
        return templateRenderer.render("prompt/time-context.txt",
                Map.of("now", nowText.get()));
    }
}