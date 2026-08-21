package com.example.dsh.browser;

import com.example.dsh.tool.AgentTool;
import com.example.dsh.tool.Tool;
import com.example.dsh.tool.ToolCall;
import com.example.dsh.tool.ToolContext;
import com.example.dsh.tool.ToolResult;
import com.example.dsh.tool.ToolSchema;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.WaitUntilState;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 浏览器导航工具 — 打开 URL、前进、后退、刷新、resize。
 */
public class BrowserNavigationTools {

    @Component
    @Tool(name = "browser_navigate", description = "在隔离浏览器会话中导航到指定 URL，返回当前 URL 与标题。")
    public static class NavigateTool extends AbstractBrowserTool {

        public NavigateTool(PlaywrightBrowserManager browserManager) {
            super(browserManager);
        }

        @Override
        public String name() {
            return "browser_navigate";
        }

        @Override
        public ToolSchema getSchema() {
            return ToolSchema.builder()
                    .name(name())
                    .description("在当前浏览器会话中导航到 URL。")
                    .addParameter("url", "string", "目标 URL（http/https）")
                    .addParameter("wait_until", "string", "等待策略: load|domcontentloaded|networkidle, 默认 load")
                    .required("url")
                    .build();
        }

        @Override
        public ToolResult execute(ToolCall call, ToolContext context) {
            String url = call.getString("url");
            if (url == null || url.isBlank()) {
                return ToolResult.failure("缺少必要参数 url");
            }
            try {
                Page page = getPage(context);
                Page.NavigateOptions opts = new Page.NavigateOptions();
                String waitUntil = call.getString("wait_until", "load");
                opts.setWaitUntil(WaitUntilState.valueOf(waitUntil.toUpperCase()));
                page.navigate(url, opts);
                return ToolResult.success("已导航到: " + page.url(), Map.of(
                        "url", page.url(),
                        "title", page.title()));
            } catch (Exception e) {
                return ToolResult.failure("导航失败: " + e.getMessage());
            }
        }
    }

    @Component
    @Tool(name = "browser_resize", description = "调整当前浏览器视口大小。")
    public static class ResizeTool extends AbstractBrowserTool {

        public ResizeTool(PlaywrightBrowserManager browserManager) {
            super(browserManager);
        }

        @Override
        public String name() {
            return "browser_resize";
        }

        @Override
        public ToolSchema getSchema() {
            return ToolSchema.builder()
                    .name(name())
                    .description("调整浏览器视口大小。")
                    .addParameter("width", "integer", "宽度（像素）")
                    .addParameter("height", "integer", "高度（像素）")
                    .required("width", "height")
                    .build();
        }

        @Override
        public ToolResult execute(ToolCall call, ToolContext context) {
            int width = call.getInt("width", 1280);
            int height = call.getInt("height", 720);
            try {
                Page page = getPage(context);
                page.setViewportSize(width, height);
                return ToolResult.success("视口已调整: " + width + "x" + height,
                        Map.of("width", width, "height", height));
            } catch (Exception e) {
                return ToolResult.failure("调整视口失败: " + e.getMessage());
            }
        }
    }

    @Component
    @Tool(name = "browser_go_back", description = "在当前浏览器会话中后退一页。")
    public static class GoBackTool extends AbstractBrowserTool {

        public GoBackTool(PlaywrightBrowserManager browserManager) {
            super(browserManager);
        }

        @Override
        public String name() {
            return "browser_go_back";
        }

        @Override
        public ToolSchema getSchema() {
            return ToolSchema.builder().name(name()).description("后退一页。").build();
        }

        @Override
        public ToolResult execute(ToolCall call, ToolContext context) {
            try {
                Page page = getPage(context);
                page.goBack();
                return success(page, "后退成功");
            } catch (Exception e) {
                return ToolResult.failure("后退失败: " + e.getMessage());
            }
        }
    }

    @Component
    @Tool(name = "browser_go_forward", description = "在当前浏览器会话中前进一页。")
    public static class GoForwardTool extends AbstractBrowserTool {

        public GoForwardTool(PlaywrightBrowserManager browserManager) {
            super(browserManager);
        }

        @Override
        public String name() {
            return "browser_go_forward";
        }

        @Override
        public ToolSchema getSchema() {
            return ToolSchema.builder().name(name()).description("前进一页。").build();
        }

        @Override
        public ToolResult execute(ToolCall call, ToolContext context) {
            try {
                Page page = getPage(context);
                page.goForward();
                return success(page, "前进成功");
            } catch (Exception e) {
                return ToolResult.failure("前进失败: " + e.getMessage());
            }
        }
    }

    @Component
    @Tool(name = "browser_refresh", description = "在当前浏览器会话中刷新当前页面。")
    public static class RefreshTool extends AbstractBrowserTool {

        public RefreshTool(PlaywrightBrowserManager browserManager) {
            super(browserManager);
        }

        @Override
        public String name() {
            return "browser_refresh";
        }

        @Override
        public ToolSchema getSchema() {
            return ToolSchema.builder().name(name()).description("刷新当前页面。").build();
        }

        @Override
        public ToolResult execute(ToolCall call, ToolContext context) {
            try {
                Page page = getPage(context);
                page.reload();
                return success(page, "刷新成功");
            } catch (Exception e) {
                return ToolResult.failure("刷新失败: " + e.getMessage());
            }
        }
    }
}