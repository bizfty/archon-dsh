package com.example.dsh.browser;

import com.example.dsh.tool.AgentTool;
import com.example.dsh.tool.Tool;
import com.example.dsh.tool.ToolCall;
import com.example.dsh.tool.ToolContext;
import com.example.dsh.tool.ToolResult;
import com.example.dsh.tool.ToolSchema;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 浏览器交互工具 — 点击、填表单、键盘、悬停、选择、勾选等。
 */
public class BrowserInteractionTools {

    @Component
    @Tool(name = "browser_click", description = "点击指定选择器的元素；返回可见文本或成功提示。")
    public static class ClickTool extends AbstractBrowserTool {

        public ClickTool(PlaywrightBrowserManager browserManager) {
            super(browserManager);
        }

        @Override
        public String name() {
            return "browser_click";
        }

        @Override
        public ToolSchema getSchema() {
            return ToolSchema.builder()
                    .name(name())
                    .description("点击匹配选择器的第一个元素。")
                    .addParameter("selector", "string", "CSS / XPath / text 选择器")
                    .addParameter("wait_after", "integer", "点击后等待毫秒（默认 500）")
                    .required("selector")
                    .build();
        }

        @Override
        public ToolResult execute(ToolCall call, ToolContext context) {
            String selector = call.getString("selector");
            if (selector == null || selector.isBlank()) {
                return ToolResult.failure("缺少必要参数 selector");
            }
            int waitAfter = call.getInt("wait_after", 500);
            try {
                Page page = getPage(context);
                Locator loc = page.locator(selector).first();
                loc.click();
                if (waitAfter > 0) {
                    Thread.sleep(waitAfter);
                }
                return ToolResult.success("点击成功: " + selector,
                        Map.of("url", page.url(), "title", page.title()));
            } catch (Exception e) {
                return ToolResult.failure("点击失败: " + e.getMessage());
            }
        }
    }

    @Component
    @Tool(name = "browser_fill_form", description = "批量填写表单字段（selector→value 映射）。")
    public static class FillFormTool extends AbstractBrowserTool {

        public FillFormTool(PlaywrightBrowserManager browserManager) {
            super(browserManager);
        }

        @Override
        public String name() {
            return "browser_fill_form";
        }

        @Override
        public ToolSchema getSchema() {
            return ToolSchema.builder()
                    .name(name())
                    .description("填写表单字段。")
                    .addParameter("fields", "object", "键为 selector，值为要填入的字符串")
                    .required("fields")
                    .build();
        }

        @Override
        public ToolResult execute(ToolCall call, ToolContext context) {
            Map<String, Object> fields = call.getMap("fields");
            if (fields == null || fields.isEmpty()) {
                return ToolResult.failure("缺少必要参数 fields");
            }
            try {
                Page page = getPage(context);
                for (Map.Entry<String, Object> entry : fields.entrySet()) {
                    String sel = entry.getKey();
                    String val = entry.getValue() == null ? "" : String.valueOf(entry.getValue());
                    page.locator(sel).first().fill(val);
                }
                return ToolResult.success("表单填写完成: " + fields.size() + " 个字段",
                        Map.of("url", page.url()));
            } catch (Exception e) {
                return ToolResult.failure("填写失败: " + e.getMessage());
            }
        }
    }

    @Component
    @Tool(name = "browser_type", description = "在指定选择器元素中键入文本（逐字符）。")
    public static class TypeTool extends AbstractBrowserTool {

        public TypeTool(PlaywrightBrowserManager browserManager) {
            super(browserManager);
        }

        @Override
        public String name() {
            return "browser_type";
        }

        @Override
        public ToolSchema getSchema() {
            return ToolSchema.builder()
                    .name(name())
                    .description("在元素中逐字符键入。")
                    .addParameter("selector", "string", "选择器")
                    .addParameter("text", "string", "要键入的文本")
                    .required("selector", "text")
                    .build();
        }

        @Override
        public ToolResult execute(ToolCall call, ToolContext context) {
            String selector = call.getString("selector");
            String text = call.getString("text");
            if (selector == null || text == null) {
                return ToolResult.failure("缺少必要参数 selector/text");
            }
            try {
                Page page = getPage(context);
                page.locator(selector).first().type(text);
                return ToolResult.success("键入完成", Map.of("url", page.url()));
            } catch (Exception e) {
                return ToolResult.failure("键入失败: " + e.getMessage());
            }
        }
    }

    @Component
    @Tool(name = "browser_press_key", description = "在页面上按下指定按键（如 Enter / Escape / ArrowDown）。")
    public static class PressKeyTool extends AbstractBrowserTool {

        public PressKeyTool(PlaywrightBrowserManager browserManager) {
            super(browserManager);
        }

        @Override
        public String name() {
            return "browser_press_key";
        }

        @Override
        public ToolSchema getSchema() {
            return ToolSchema.builder()
                    .name(name())
                    .description("按下按键。")
                    .addParameter("key", "string", "按键名（Enter/Escape/F1 等）")
                    .addParameter("selector", "string", "可选：指定元素聚焦后按键")
                    .required("key")
                    .build();
        }

        @Override
        public ToolResult execute(ToolCall call, ToolContext context) {
            String key = call.getString("key");
            String selector = call.getString("selector");
            if (key == null) {
                return ToolResult.failure("缺少必要参数 key");
            }
            try {
                Page page = getPage(context);
                if (selector != null && !selector.isBlank()) {
                    page.locator(selector).first().press(key);
                } else {
                    page.keyboard().press(key);
                }
                return ToolResult.success("按键 " + key + " 已按下", Map.of("url", page.url()));
            } catch (Exception e) {
                return ToolResult.failure("按键失败: " + e.getMessage());
            }
        }
    }

    @Component
    @Tool(name = "browser_hover", description = "将鼠标悬停到指定选择器的元素上。")
    public static class HoverTool extends AbstractBrowserTool {

        public HoverTool(PlaywrightBrowserManager browserManager) {
            super(browserManager);
        }

        @Override
        public String name() {
            return "browser_hover";
        }

        @Override
        public ToolSchema getSchema() {
            return ToolSchema.builder()
                    .name(name())
                    .description("悬停到元素。")
                    .addParameter("selector", "string", "选择器")
                    .required("selector")
                    .build();
        }

        @Override
        public ToolResult execute(ToolCall call, ToolContext context) {
            String selector = call.getString("selector");
            if (selector == null) {
                return ToolResult.failure("缺少必要参数 selector");
            }
            try {
                Page page = getPage(context);
                page.locator(selector).first().hover();
                return ToolResult.success("悬停成功", Map.of("url", page.url()));
            } catch (Exception e) {
                return ToolResult.failure("悬停失败: " + e.getMessage());
            }
        }
    }

    @Component
    @Tool(name = "browser_select", description = "在 select 元素中按可见文本或值选择选项。")
    public static class SelectTool extends AbstractBrowserTool {

        public SelectTool(PlaywrightBrowserManager browserManager) {
            super(browserManager);
        }

        @Override
        public String name() {
            return "browser_select";
        }

        @Override
        public ToolSchema getSchema() {
            return ToolSchema.builder()
                    .name(name())
                    .description("选择 select 选项。")
                    .addParameter("selector", "string", "select 选择器")
                    .addParameter("value", "string", "选项的值或可见文本")
                    .required("selector", "value")
                    .build();
        }

        @Override
        public ToolResult execute(ToolCall call, ToolContext context) {
            String selector = call.getString("selector");
            String value = call.getString("value");
            if (selector == null || value == null) {
                return ToolResult.failure("缺少必要参数 selector/value");
            }
            try {
                Page page = getPage(context);
                page.locator(selector).first().selectOption(value);
                return ToolResult.success("选择成功", Map.of("url", page.url()));
            } catch (Exception e) {
                return ToolResult.failure("选择失败: " + e.getMessage());
            }
        }
    }

    @Component
    @Tool(name = "browser_check", description = "将复选框/单选框选中。")
    public static class CheckTool extends AbstractBrowserTool {

        public CheckTool(PlaywrightBrowserManager browserManager) {
            super(browserManager);
        }

        @Override
        public String name() {
            return "browser_check";
        }

        @Override
        public ToolSchema getSchema() {
            return ToolSchema.builder()
                    .name(name())
                    .description("选中 checkbox/radio。")
                    .addParameter("selector", "string", "选择器")
                    .required("selector")
                    .build();
        }

        @Override
        public ToolResult execute(ToolCall call, ToolContext context) {
            String selector = call.getString("selector");
            if (selector == null) {
                return ToolResult.failure("缺少必要参数 selector");
            }
            try {
                Page page = getPage(context);
                page.locator(selector).first().check();
                return ToolResult.success("选中成功", Map.of("url", page.url()));
            } catch (Exception e) {
                return ToolResult.failure("选中失败: " + e.getMessage());
            }
        }
    }

    @Component
    @Tool(name = "browser_uncheck", description = "将复选框取消选中。")
    public static class UncheckTool extends AbstractBrowserTool {

        public UncheckTool(PlaywrightBrowserManager browserManager) {
            super(browserManager);
        }

        @Override
        public String name() {
            return "browser_uncheck";
        }

        @Override
        public ToolSchema getSchema() {
            return ToolSchema.builder()
                    .name(name())
                    .description("取消选中 checkbox。")
                    .addParameter("selector", "string", "选择器")
                    .required("selector")
                    .build();
        }

        @Override
        public ToolResult execute(ToolCall call, ToolContext context) {
            String selector = call.getString("selector");
            if (selector == null) {
                return ToolResult.failure("缺少必要参数 selector");
            }
            try {
                Page page = getPage(context);
                page.locator(selector).first().uncheck();
                return ToolResult.success("取消选中成功", Map.of("url", page.url()));
            } catch (Exception e) {
                return ToolResult.failure("取消选中失败: " + e.getMessage());
            }
        }
    }
}