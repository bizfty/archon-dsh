package com.bizfty.anchon.dsh.browser;

import com.bizfty.anchon.dsh.tool.AgentTool;
import com.bizfty.anchon.dsh.tool.Tool;
import com.bizfty.anchon.dsh.tool.ToolCall;
import com.bizfty.anchon.dsh.tool.ToolContext;
import com.bizfty.anchon.dsh.tool.ToolResult;
import com.bizfty.anchon.dsh.tool.ToolSchema;
import com.microsoft.playwright.ElementHandle;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.WaitForSelectorState;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 浏览器内容工具 — 截图、获取文本/HTML、执行 JS、等待元素、上传文件、下载、cookie/localStorage 管理。
 */
public class BrowserContentTools {

    @Component
    @Tool(name = "browser_screenshot", description = "对当前页面截图，返回 base64 PNG 或保存到文件。")
    public static class ScreenshotTool extends AbstractBrowserTool {

        public ScreenshotTool(PlaywrightBrowserManager browserManager) {
            super(browserManager);
        }

        @Override
        public String name() {
            return "browser_screenshot";
        }

        @Override
        public ToolSchema getSchema() {
            return ToolSchema.builder()
                    .name(name())
                    .description("截图。")
                    .addParameter("save_path", "string", "保存路径（可选；不提供时返回 base64）")
                    .addParameter("full_page", "boolean", "是否整页截图（默认 false）")
                    .build();
        }

        @Override
        public ToolResult execute(ToolCall call, ToolContext context) {
            try {
                Page page = getPage(context);
                boolean fullPage = call.getBool("full_page", false);
                String savePath = call.getString("save_path");
                byte[] bytes;
                if (savePath != null && !savePath.isBlank()) {
                    Path p = Paths.get(savePath);
                    if (p.getParent() != null) {
                        Files.createDirectories(p.getParent());
                    }
                    page.screenshot(new Page.ScreenshotOptions()
                            .setPath(p)
                            .setFullPage(fullPage));
                    return ToolResult.success("截图已保存: " + savePath,
                            Map.of("path", savePath, "full_page", fullPage));
                }
                bytes = page.screenshot(new Page.ScreenshotOptions().setFullPage(fullPage));
                String base64 = java.util.Base64.getEncoder().encodeToString(bytes);
                return ToolResult.success("截图完成 (base64)",
                        Map.of("base64", base64, "size", bytes.length, "full_page", fullPage));
            } catch (Exception e) {
                return ToolResult.failure("截图失败: " + e.getMessage());
            }
        }
    }

    @Component
    @Tool(name = "browser_get_text", description = "获取当前页面可见文本或指定选择器元素的文本。")
    public static class GetTextTool extends AbstractBrowserTool {

        public GetTextTool(PlaywrightBrowserManager browserManager) {
            super(browserManager);
        }

        @Override
        public String name() {
            return "browser_get_text";
        }

        @Override
        public ToolSchema getSchema() {
            return ToolSchema.builder()
                    .name(name())
                    .description("获取文本。")
                    .addParameter("selector", "string", "选择器（留空取整页）")
                    .build();
        }

        @Override
        public ToolResult execute(ToolCall call, ToolContext context) {
            try {
                Page page = getPage(context);
                String selector = call.getString("selector");
                String text;
                if (selector == null || selector.isBlank()) {
                    text = page.innerText("body");
                } else {
                    text = page.locator(selector).first().innerText();
                }
                int limit = 20_000;
                String truncated = text.length() > limit ? text.substring(0, limit) + "...[truncated]" : text;
                return ToolResult.success("获取文本成功",
                        Map.of("text", truncated, "length", text.length(), "url", page.url()));
            } catch (Exception e) {
                return ToolResult.failure("获取文本失败: " + e.getMessage());
            }
        }
    }

    @Component
    @Tool(name = "browser_get_html", description = "获取当前页面 HTML 或指定选择器元素的 innerHTML。")
    public static class GetHtmlTool extends AbstractBrowserTool {

        public GetHtmlTool(PlaywrightBrowserManager browserManager) {
            super(browserManager);
        }

        @Override
        public String name() {
            return "browser_get_html";
        }

        @Override
        public ToolSchema getSchema() {
            return ToolSchema.builder()
                    .name(name())
                    .description("获取 HTML。")
                    .addParameter("selector", "string", "选择器（留空取整页 outerHTML）")
                    .build();
        }

        @Override
        public ToolResult execute(ToolCall call, ToolContext context) {
            try {
                Page page = getPage(context);
                String selector = call.getString("selector");
                String html;
                if (selector == null || selector.isBlank()) {
                    html = page.content();
                } else {
                    html = page.locator(selector).first().innerHTML();
                }
                int limit = 40_000;
                String truncated = html.length() > limit ? html.substring(0, limit) + "...[truncated]" : html;
                return ToolResult.success("获取 HTML 成功",
                        Map.of("html", truncated, "length", html.length()));
            } catch (Exception e) {
                return ToolResult.failure("获取 HTML 失败: " + e.getMessage());
            }
        }
    }

    @Component
    @Tool(name = "browser_eval_js", description = "在浏览器上下文执行任意 JS 表达式，返回结果。")
    public static class EvalJsTool extends AbstractBrowserTool {

        public EvalJsTool(PlaywrightBrowserManager browserManager) {
            super(browserManager);
        }

        @Override
        public String name() {
            return "browser_eval_js";
        }

        @Override
        public ToolSchema getSchema() {
            return ToolSchema.builder()
                    .name(name())
                    .description("执行 JS。")
                    .addParameter("script", "string", "JS 表达式或函数体")
                    .required("script")
                    .build();
        }

        @Override
        public ToolResult execute(ToolCall call, ToolContext context) {
            String script = call.getString("script");
            if (script == null || script.isBlank()) {
                return ToolResult.failure("缺少必要参数 script");
            }
            try {
                Page page = getPage(context);
                Object result = page.evaluate(script);
                String resultStr = result == null ? "null" : result.toString();
                int limit = 20_000;
                if (resultStr.length() > limit) {
                    resultStr = resultStr.substring(0, limit) + "...[truncated]";
                }
                return ToolResult.success("JS 执行成功", Map.of("result", resultStr));
            } catch (Exception e) {
                return ToolResult.failure("JS 执行失败: " + e.getMessage());
            }
        }
    }

    @Component
    @Tool(name = "browser_wait_for", description = "等待指定选择器出现、消失或可点击。")
    public static class WaitForTool extends AbstractBrowserTool {

        public WaitForTool(PlaywrightBrowserManager browserManager) {
            super(browserManager);
        }

        @Override
        public String name() {
            return "browser_wait_for";
        }

        @Override
        public ToolSchema getSchema() {
            return ToolSchema.builder()
                    .name(name())
                    .description("等待元素。")
                    .addParameter("selector", "string", "选择器")
                    .addParameter("state", "string", "状态: visible|hidden|attached|detached, 默认 visible")
                    .addParameter("timeout_ms", "integer", "超时毫秒（默认 10000）")
                    .required("selector")
                    .build();
        }

        @Override
        public ToolResult execute(ToolCall call, ToolContext context) {
            String selector = call.getString("selector");
            if (selector == null) {
                return ToolResult.failure("缺少必要参数 selector");
            }
            String state = call.getString("state", "visible");
            int timeoutMs = call.getInt("timeout_ms", 10_000);
            try {
                Page page = getPage(context);
                Locator loc = page.locator(selector).first();
                loc.waitFor(new Locator.WaitForOptions()
                        .setState(WaitForSelectorState.valueOf(state.toUpperCase()))
                        .setTimeout(timeoutMs));
                return ToolResult.success("等待成功: " + selector + " [" + state + "]",
                        Map.of("selector", selector, "state", state));
            } catch (Exception e) {
                return ToolResult.failure("等待失败: " + e.getMessage());
            }
        }
    }

    @Component
    @Tool(name = "browser_upload_file", description = "为 input[type=file] 元素设置要上传的文件。")
    public static class UploadFileTool extends AbstractBrowserTool {

        public UploadFileTool(PlaywrightBrowserManager browserManager) {
            super(browserManager);
        }

        @Override
        public String name() {
            return "browser_upload_file";
        }

        @Override
        public ToolSchema getSchema() {
            return ToolSchema.builder()
                    .name(name())
                    .description("上传文件。")
                    .addParameter("selector", "string", "file input 选择器")
                    .addParameter("file_paths", "string", "文件路径（多文件用 JSON 数组）")
                    .required("selector", "file_paths")
                    .build();
        }

        @Override
        public ToolResult execute(ToolCall call, ToolContext context) {
            String selector = call.getString("selector");
            String filePaths = call.getString("file_paths");
            if (selector == null || filePaths == null) {
                return ToolResult.failure("缺少必要参数 selector/file_paths");
            }
            try {
                Page page = getPage(context);
                List<String> paths;
                if (filePaths.startsWith("[")) {
                    paths = new com.bizfty.anchon.dsh.util.JsonUtils().toStringList(filePaths);
                } else {
                    paths = List.of(filePaths);
                }
                page.locator(selector).first().setInputFiles(paths.stream().map(Paths::get).toArray(Path[]::new));
                return ToolResult.success("文件已上传",
                        Map.of("files", paths.size(), "selector", selector));
            } catch (Exception e) {
                return ToolResult.failure("上传失败: " + e.getMessage());
            }
        }
    }

    @Component
    @Tool(name = "browser_download", description = "点击下载链接并保存文件。")
    public static class DownloadTool extends AbstractBrowserTool {

        public DownloadTool(PlaywrightBrowserManager browserManager) {
            super(browserManager);
        }

        @Override
        public String name() {
            return "browser_download";
        }

        @Override
        public ToolSchema getSchema() {
            return ToolSchema.builder()
                    .name(name())
                    .description("下载文件。")
                    .addParameter("selector", "string", "下载链接选择器")
                    .addParameter("save_dir", "string", "保存目录（默认会话目录）")
                    .required("selector")
                    .build();
        }

        @Override
        public ToolResult execute(ToolCall call, ToolContext context) {
            String selector = call.getString("selector");
            String saveDir = call.getString("save_dir", "/tmp/dsh-downloads");
            if (selector == null) {
                return ToolResult.failure("缺少必要参数 selector");
            }
            try {
                Page page = getPage(context);
                ElementHandle handle = page.locator(selector).first().elementHandle();
                if (handle == null) {
                    return ToolResult.failure("元素未找到: " + selector);
                }
                String fileName = handle.asElement().getAttribute("href");
                String name = fileName != null ? Paths.get(fileName).getFileName().toString() : "download.bin";
                Path target = Paths.get(saveDir, name);
                java.nio.file.Files.createDirectories(Paths.get(saveDir));
                com.microsoft.playwright.Download dl = page.waitForDownload(() -> handle.click());
                dl.saveAs(target);
                return ToolResult.success("下载完成", Map.of("saved", target.toString()));
            } catch (Exception e) {
                return ToolResult.failure("下载失败: " + e.getMessage());
            }
        }
    }
}