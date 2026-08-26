package com.bizfty.anchon.dsh.browser;

import com.bizfty.anchon.dsh.tool.AgentTool;
import com.bizfty.anchon.dsh.tool.ToolContext;
import com.bizfty.anchon.dsh.tool.ToolResult;
import com.microsoft.playwright.Page;

/**
 * 浏览器工具基类 — 提取 session → page，子类实现具体操作。
 */
public abstract class AbstractBrowserTool implements AgentTool {

    protected final PlaywrightBrowserManager browserManager;

    protected AbstractBrowserTool(PlaywrightBrowserManager browserManager) {
        this.browserManager = browserManager;
    }

    protected Page getPage(ToolContext context) {
        if (context.sessionId() == null) {
            throw new IllegalStateException("browser tools require a sessionId");
        }
        return browserManager.getPage(context.sessionId().value());
    }

    protected ToolResult success(Page page, String message) {
        return ToolResult.success(message, java.util.Map.of(
                "url", page.url(),
                "title", page.title()));
    }

    protected ToolResult failure(String message) {
        return ToolResult.failure(message);
    }
}