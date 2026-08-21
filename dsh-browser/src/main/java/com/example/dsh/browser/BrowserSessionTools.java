package com.example.dsh.browser;

import com.example.dsh.tool.AgentTool;
import com.example.dsh.tool.Tool;
import com.example.dsh.tool.ToolCall;
import com.example.dsh.tool.ToolContext;
import com.example.dsh.tool.ToolResult;
import com.example.dsh.tool.ToolSchema;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.Cookie;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 浏览器会话工具 — 对话框处理、cookie/localStorage、会话关闭、storageState 保存。
 */
public class BrowserSessionTools {

    @Component
    @Tool(name = "browser_accept_dialog", description = "接受下一个对话框（alert/confirm/prompt）。")
    public static class AcceptDialogTool extends AbstractBrowserTool {

        public AcceptDialogTool(PlaywrightBrowserManager browserManager) {
            super(browserManager);
        }

        @Override
        public String name() {
            return "browser_accept_dialog";
        }

        @Override
        public ToolSchema getSchema() {
            return ToolSchema.builder()
                    .name(name())
                    .description("接受下一个对话框。")
                    .addParameter("prompt_text", "string", "prompt 对话框要填入的文本")
                    .build();
        }

        @Override
        public ToolResult execute(ToolCall call, ToolContext context) {
            try {
                Page page = getPage(context);
                page.onDialog(d -> {
                    String text = call.getString("prompt_text");
                    if (text != null) {
                        d.accept(text);
                    } else {
                        d.accept();
                    }
                });
                return ToolResult.success("对话框接受已安排", Map.of());
            } catch (Exception e) {
                return ToolResult.failure("安排失败: " + e.getMessage());
            }
        }
    }

    @Component
    @Tool(name = "browser_dismiss_dialog", description = "拒绝下一个对话框。")
    public static class DismissDialogTool extends AbstractBrowserTool {

        public DismissDialogTool(PlaywrightBrowserManager browserManager) {
            super(browserManager);
        }

        @Override
        public String name() {
            return "browser_dismiss_dialog";
        }

        @Override
        public ToolSchema getSchema() {
            return ToolSchema.builder()
                    .name(name())
                    .description("拒绝下一个对话框。")
                    .build();
        }

        @Override
        public ToolResult execute(ToolCall call, ToolContext context) {
            try {
                Page page = getPage(context);
                page.onDialog(d -> d.dismiss());
                return ToolResult.success("对话框拒绝已安排", Map.of());
            } catch (Exception e) {
                return ToolResult.failure("安排失败: " + e.getMessage());
            }
        }
    }

    @Component
    @Tool(name = "browser_get_cookies", description = "获取当前会话的 cookies（可按 URL 过滤）。")
    public static class GetCookiesTool extends AbstractBrowserTool {

        public GetCookiesTool(PlaywrightBrowserManager browserManager) {
            super(browserManager);
        }

        @Override
        public String name() {
            return "browser_get_cookies";
        }

        @Override
        public ToolSchema getSchema() {
            return ToolSchema.builder()
                    .name(name())
                    .description("获取 cookies。")
                    .addParameter("urls", "string", "可选：JSON 数组 URL 过滤")
                    .build();
        }

        @Override
        public ToolResult execute(ToolCall call, ToolContext context) {
            try {
                Page page = getPage(context);
                BrowserContext ctx = page.context();
                List<Cookie> cookies;
                String urls = call.getString("urls");
                if (urls != null && !urls.isBlank()) {
                    List<String> urlList = new com.example.dsh.util.JsonUtils().toStringList(urls);
                    cookies = ctx.cookies(urlList);
                } else {
                    cookies = ctx.cookies();
                }
                List<Map<String, Object>> out = new ArrayList<>();
                for (Cookie c : cookies) {
                    Map<String, Object> m = new HashMap<>();
                    m.put("name", c.name);
                    m.put("value", c.value);
                    m.put("domain", c.domain);
                    m.put("path", c.path);
                    m.put("expires", c.expires);
                    m.put("httpOnly", c.httpOnly);
                    m.put("secure", c.secure);
                    m.put("sameSite", c.sameSite == null ? null : c.sameSite.name());
                    out.add(m);
                }
                return ToolResult.success("获取 cookies 成功",
                        Map.of("count", out.size(), "cookies", out));
            } catch (Exception e) {
                return ToolResult.failure("获取 cookies 失败: " + e.getMessage());
            }
        }
    }

    @Component
    @Tool(name = "browser_add_cookies", description = "向浏览器会话添加 cookies。")
    public static class AddCookiesTool extends AbstractBrowserTool {

        public AddCookiesTool(PlaywrightBrowserManager browserManager) {
            super(browserManager);
        }

        @Override
        public String name() {
            return "browser_add_cookies";
        }

        @Override
        public ToolSchema getSchema() {
            return ToolSchema.builder()
                    .name(name())
                    .description("添加 cookies。")
                    .addParameter("cookies", "array", "cookie 对象数组（name/value/domain/path 等）")
                    .required("cookies")
                    .build();
        }

        @Override
        public ToolResult execute(ToolCall call, ToolContext context) {
            List<Map<String, Object>> cookies = call.getList("cookies");
            if (cookies == null || cookies.isEmpty()) {
                return ToolResult.failure("缺少必要参数 cookies");
            }
            try {
                Page page = getPage(context);
                BrowserContext ctx = page.context();
                List<Cookie> list = new ArrayList<>();
                for (Map<String, Object> c : cookies) {
                    Cookie nc = new Cookie(c.get("name").toString(), c.get("value").toString())
                            .setDomain(c.get("domain") == null ? "" : c.get("domain").toString())
                            .setPath(c.get("path") == null ? "/" : c.get("path").toString());
                    if (c.get("httpOnly") != null) {
                        nc.setHttpOnly(Boolean.parseBoolean(c.get("httpOnly").toString()));
                    }
                    if (c.get("secure") != null) {
                        nc.setSecure(Boolean.parseBoolean(c.get("secure").toString()));
                    }
                    if (c.get("expires") != null) {
                        nc.setExpires(Double.parseDouble(c.get("expires").toString()));
                    }
                    list.add(nc);
                }
                ctx.addCookies(list);
                return ToolResult.success("添加 cookies 成功", Map.of("count", list.size()));
            } catch (Exception e) {
                return ToolResult.failure("添加 cookies 失败: " + e.getMessage());
            }
        }
    }

    @Component
    @Tool(name = "browser_get_localstorage", description = "获取当前页面的 localStorage 键值对。")
    public static class GetLocalStorageTool extends AbstractBrowserTool {

        public GetLocalStorageTool(PlaywrightBrowserManager browserManager) {
            super(browserManager);
        }

        @Override
        public String name() {
            return "browser_get_localstorage";
        }

        @Override
        public ToolSchema getSchema() {
            return ToolSchema.builder()
                    .name(name())
                    .description("读取 localStorage。")
                    .build();
        }

        @Override
        public ToolResult execute(ToolCall call, ToolContext context) {
            try {
                Page page = getPage(context);
                Object obj = page.evaluate("""
                        (() => {
                          const o = {};
                          for (let i = 0; i < localStorage.length; i++) {
                            const k = localStorage.key(i);
                            o[k] = localStorage.getItem(k);
                          }
                          return o;
                        })()
                        """);
                return ToolResult.success("读取 localStorage 成功", Map.of("storage", obj));
            } catch (Exception e) {
                return ToolResult.failure("读取 localStorage 失败: " + e.getMessage());
            }
        }
    }

    @Component
    @Tool(name = "browser_save_state", description = "将当前会话 storageState（cookies/localStorage）持久化到磁盘，后续会话可恢复。")
    public static class SaveStateTool extends AbstractBrowserTool {

        public SaveStateTool(PlaywrightBrowserManager browserManager) {
            super(browserManager);
        }

        @Override
        public String name() {
            return "browser_save_state";
        }

        @Override
        public ToolSchema getSchema() {
            return ToolSchema.builder()
                    .name(name())
                    .description("保存 storageState。")
                    .addParameter("path", "string", "保存路径（可选；默认 dsh.browser.storageStateDir）")
                    .build();
        }

        @Override
        public ToolResult execute(ToolCall call, ToolContext context) {
            if (context.sessionId() == null) {
                return ToolResult.failure("缺少 sessionId");
            }
            try {
                String sessionId = context.sessionId().value();
                Page page = getPage(context);
                String path = call.getString("path");
                Path target;
                if (path != null && !path.isBlank()) {
                    target = Paths.get(path);
                } else {
                    target = Paths.get("/tmp/dsh-browser-storage", sessionId + ".json");
                }
                Files.createDirectories(target.getParent());
                page.context().storageState(new BrowserContext.StorageStateOptions().setPath(target));
                return ToolResult.success("storageState 已保存: " + target,
                        Map.of("path", target.toString()));
            } catch (Exception e) {
                return ToolResult.failure("保存失败: " + e.getMessage());
            }
        }
    }

    @Component
    @Tool(name = "browser_close_session", description = "关闭当前浏览器会话（释放浏览器上下文）。")
    public static class CloseSessionTool extends AbstractBrowserTool {

        public CloseSessionTool(PlaywrightBrowserManager browserManager) {
            super(browserManager);
        }

        @Override
        public String name() {
            return "browser_close_session";
        }

        @Override
        public ToolSchema getSchema() {
            return ToolSchema.builder()
                    .name(name())
                    .description("关闭当前浏览器会话。")
                    .build();
        }

        @Override
        public ToolResult execute(ToolCall call, ToolContext context) {
            if (context.sessionId() == null) {
                return ToolResult.failure("缺少 sessionId");
            }
            browserManager.closeSession(context.sessionId().value());
            return ToolResult.success("浏览器会话已关闭", Map.of());
        }
    }
}