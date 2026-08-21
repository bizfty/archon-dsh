package com.example.dsh.context;

import com.example.dsh.core.prompt.SystemPromptContext;
import com.example.dsh.core.prompt.SystemPromptSection;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.function.Supplier;

/**
 * 时间上下文段（order -50）— 每次组装注入当前时间（对应 DSH context/time-context；
 * DSH 为每 step 注入，本实现为每 turn 注入，语义等价于 step 1 采样）。
 */
@Component
public class TimeContextSection implements SystemPromptSection {

    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z");

    private final Supplier<String> nowText;

    public TimeContextSection() {
        this(Clock.systemDefaultZone());
    }

    public TimeContextSection(Clock clock) {
        this.nowText = () -> FORMATTER.format(clock.instant().atZone(ZoneId.systemDefault()));
    }

    /** 测试用：固定时间。 */
    public TimeContextSection(String fixedTime) {
        this.nowText = () -> fixedTime;
    }

    @Override
    public int order() {
        return -50;
    }

    @Override
    public String render(SystemPromptContext context) {
        return "当前时间: " + nowText.get() + "\n";
    }
}
