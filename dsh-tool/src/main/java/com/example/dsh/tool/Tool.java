package com.example.dsh.tool;

import org.springframework.stereotype.Component;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 工具注解 — 标注在 {@link AgentTool} 实现类上，ToolRegistry 启动时自动注册。
 * <p>
 * 注意：这是本项目自定义注解（DSH 风格），不是 Spring AI 的
 * {@code org.springframework.ai.tool.annotation.Tool} — 二者作用域不同：
 * 本注解负责注册到 ToolRegistry；Spring AI 侧由 AgentToolCallback 适配。
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Component
public @interface Tool {

    /** 工具名称（LLM 调用名，如 todo_write）。 */
    String name();

    /** 工具描述（注入 LLM 帮助理解用途）。 */
    String description() default "";

    /** 是否需要人工审批后才执行（默认否）。 */
    boolean requiresApproval() default false;

    /** 执行超时毫秒（>0 时由执行管线强制超时，默认 0=不限）。 */
    long timeoutMs() default 0;
}
