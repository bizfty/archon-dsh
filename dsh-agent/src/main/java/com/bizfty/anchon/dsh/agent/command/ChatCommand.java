package com.bizfty.anchon.dsh.agent.command;

import com.bizfty.anchon.dsh.core.model.SessionId;

/**
 * 聊天命令（M6，design-extension-points.md §3.2/§8 D3 定案）：`/<name>` 形态，命令文本不进模型。
 * 实现由 Spring 收集进 {@link CommandRegistry}；execute 返回人类可读文本（null 表示不处理）。
 * 对比上游 commands/*：本里程碑只统一识别与分发，不做参数解析/权限（命令面未膨胀）。
 */
public interface ChatCommand {

    /** 命令名（不含前导 /；小写）。 */
    String name();

    /** 一行说明（/help 列出）。 */
    String description();

    /** 执行命令：args 为命令名后剩余文本（无参命令通常校验空）。返回结果文本。 */
    String execute(SessionId sessionId, String args);
}
